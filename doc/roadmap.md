# Roadmap

**What is not built yet.** Only future work lives here — what already
shipped is in [CHANGELOG.md](../CHANGELOG.md), what the plugin does today
is in [README.md](../README.md) and [configuration.md](configuration.md),
and how it is put together is in [architecture.md](architecture.md). When an
item ships, delete its section and write the CHANGELOG entry instead.

Items are ordered by value, best first. Each one states the problem, what
is known to be feasible, and the effort. Pick one and ship it on its own
branch.

## Tell an infrastructure failure from a broken build

**Problem.** Everything that is not green becomes `failure`, so a lost
agent, a checkout that could not reach the VCS or an unreadable artifact
dependency turns a pull request red exactly like a failing test. The
reviewer cannot tell "your code is broken" from "our CI broke", which is
the difference between fixing a commit and re-running a build.

**Feasible, verified.** `BuildProblemData.getType()` carries the problem
type, and `jetbrains.buildServer.messages.ErrorData` declares the
constants (`SNAPSHOT_DEPENDENCY_ERROR_TYPE`,
`CHECKING_FOR_CHANGES_ERROR_TYPE`, `UPDATE_SOURCES_TYPE`,
`PREPARATION_FAILURE_TYPE`, `INACCESSIBLE_EXTERNAL_DEPENDENCY_ERROR_TYPE`,
`BUILD_RUNNER_ERROR_TYPE`, …) plus a `TYPE_DESCRIPTIONS` map of
human-readable labels, ready for a Check Run title.

**Design.** Classify the build's failure reasons; when the failure is
infrastructural, say so in the title ("Build failed: could not update
sources") and conclude `neutral` instead of `failure`, so a CI hiccup
does not block a merge. Behind a flag: whether an infra failure should
still be red is a policy call, not a fact.

Groundwork for an "automatically retry an infrastructure failure"
feature, which becomes nearly free once the classification exists.

**Effort.** Small. A pure classifier plus a branch in the outcome mapping.

## Cancel builds a new push made obsolete

**Problem.** On `pull_request.synchronize` the builds already **running**
on the previous head keep an agent busy to produce a verdict about a
commit nobody will look at again, and leave an `in_progress` Check Run on
it. TeamCity drops obsolete *queued* builds by itself; started ones it
keeps.

**Design.** On `synchronize`, find the running builds of that PR's ref
whose revision is not the new head and cancel them with a comment;
`buildInterrupted` already publishes the cancellation. Only builds the
bridge could have started: never a personal build, never one somebody
started by hand — the same scope invariant `QueueCleanupPolicy` holds for
the queue.

**Effort.** Small to medium. The listener already resolves the PR's ref
and head; the care goes into the "what am I allowed to cancel" gate and
into not fighting TeamCity's own obsolete-build handling.

## Report flaky tests as flaky

**Problem.** A test that fails once and passes on retry is reported as a
failure, and a reviewer goes looking for a bug that is not there.

**Feasible.** `STestRun.getInvocationCount()` and
`getFailedInvocationCount()` are already loaded with the test statistics
the Check Run reads — a run that failed some but not all invocations is
flaky.

**Effort.** Very small. A counter and a line in the existing test section.

## Report code coverage and its trend

**Problem.** Coverage is measured on the agent and stays in TeamCity;
the pull request says nothing about it.

**Feasible.** `SBuild.getStatisticValues()` exposes coverage and every
custom statistic a build reports through
`##teamcity[buildStatisticValue]`. The previous build of the same
configuration gives the delta.

**Design.** One line in the Check Run body — "Coverage 78.4 % (+1.2 pt vs
#142)" — and nothing at all when the build reports no such statistic.

**Effort.** Small. Only worth doing if the team actually measures
coverage.

## Buttons on the Check Run

**Problem.** Acting on a build from the pull request means leaving it for
TeamCity.

**Feasible.** A Check Run accepts up to three `actions`, and GitHub
posts `check_run.requested_action` when one is clicked. The plugin
already handles `check_run.rerequested` and `check_suite.rerequested`, so
the inbound plumbing exists.

**Design.** "Rebuild without cache", "Stop build". GitHub's own re-run
button already covers the common case, so this is convenience, not
capability.

**Effort.** Small.

## Merge-queue support

**Problem.** GitHub's merge queue builds on `refs/heads/gh-readonly-queue/…`
refs and announces them with `merge_group.checks_requested`. A bridge
that ignores that event leaves the queue waiting for checks that never
arrive — it blocks, it does not degrade.

**Design.** Handle `merge_group.checks_requested` like a PR event on the
queue's temporary ref, and report on it. Needs the ref family in the VCS
root's branch spec.

**Effort.** Medium. Worth doing *before* enabling a merge queue, not
after.

## Release pipeline

**Problem.** Releases are produced by hand: bump the version, package,
attach the zip to a GitHub Release.

**Design.** A GitHub Actions workflow on `v*` tags: run `./dev test` and
`./dev package`, check the zip name matches the tag, create the Release
with notes from [CHANGELOG.md](../CHANGELOG.md), attach the zip. Simplest
on `ubuntu-latest` with Maven 3.9 + JDK 21 installed directly rather than
Docker-in-Docker.

**Effort.** Small. One workflow file.

## End-to-end fixture against a real TeamCity

**Problem.** The unit tests cover pure logic. Everything that touches the
SDK — Spring wiring, `BuildServerAdapter` callbacks, `removeFromQueue`,
the webhook endpoint's anonymous registration — is only ever exercised by
installing the plugin on a live server. Every lag bug found so far
(`finishDate` null at `buildFinished`, a stale status descriptor) was
found in production for exactly this reason.

**Design.** `org.jetbrains.teamcity:tests-support` spins up an in-memory
server in tests.

**Constraint.** It pulls in a large part of TeamCity's server jar graph
and takes ~30 s per test class to start, so it belongs in the `verify`
phase, not in `./dev test`.

**Effort.** Large — and the item with the best ratio of bugs-caught to
cleverness-required, given the class of bug listed above.

## Loose ends

- **Attribute the queue wait properly.** The Check Run splits the wait
  into "dependencies" and "free agent", and the agent share comes from
  TeamCity's queue wait-reason **build statistics** — whose key naming and
  unit the open SDK does not declare. `agentWaitHint` therefore matches
  defensively and errs low, so unexplained wait shows up as "other". The
  DEBUG lines `carries no queue wait-reason statistic; keys present: […]`
  and `agent-wait statistics: …` exist to identify the real keys from a
  live server; once known, wire them.
- **Document the implicit agent requirement trap.** A build-number pattern
  referencing `teamcity.github.bridge.pullRequest.*` becomes an implicit
  agent requirement ("must have a value") on a configuration that does not
  carry the bridge feature, and the build then finds no compatible agent.
  Worth a section in [troubleshooting.md](troubleshooting.md).

## Blocked on JetBrains

Re-check on each TeamCity release; nothing to do until then.

| Question | What we want | Status as of TC 2026.1 |
|---|---|---|
| A public `BuildBranchInfoProvider` | Override what the **Branch** column displays. Largely moot since `prBuildRef=branch` makes the column show the real branch name; it would only help projects staying on the `pull/N` model. | Absent. `BranchDisplayNameProvider` too; `Branch.getDisplayName()` is read-only and `setDesiredBranchName()` rewrites the ref itself, not its display. |
| A place to put an outbound link on a build | Link a build to its pull request from TeamCity. | Dead end, twice over: a tag **is** a filter and the React pages bind that by delegation on an ancestor (its capture listener wins), while `PlaceId.BUILD_SUMMARY` / `BUILD_ACTIONS` render on the **classic** build page only. Any new attempt has to be verified on a Sakura page first. |
| `ConnectionCredentialsFactory` for GitHub App | Token acquisition that does not need our own JWT self-mint path. | Unsupported (`Unsupported Connection Provider type: GitHubApp`). Worked around by self-minting. If it lands, the self-mint primary path can go and the credentials-manager fallback suffices again. |

## Recording a new idea

Open a GitHub issue with the `enhancement` label, sketch the plan there,
then mirror it here as a section — value first, with what makes it
feasible. This file stays the single answer to "what's next".
