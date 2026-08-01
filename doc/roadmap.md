# Roadmap

**What is not built yet.** Only future work lives here — what already
shipped is in [CHANGELOG.md](../CHANGELOG.md), what the plugin does today
is in [README.md](../README.md) and [configuration.md](configuration.md),
and how it is put together is in [architecture.md](architecture.md). When an
item ships, delete its section and write the CHANGELOG entry instead.

Items are ordered by value, best first. Each one states the problem, what
is known to be feasible, and the effort. Pick one and ship it on its own
branch.

## Warn when a required check can never arrive

**Problem.** A branch protection rule requiring a check named
`TeamCity / Sandbox / test_ci / PR / Test (Linux)` blocks every pull request
for ever if the bridge posts `TeamCity / Sandbox / test_ci / PR / Test /
Linux / Test (Linux, x64, Release)`. Nothing reports the mismatch: the PR
just sits there, "Required statuses must pass", waiting for a row that will
never exist. Rename a build configuration and you have created this without
touching anything called "GitHub" — and since 1.10.0 a project can also
rename every one of its checks at once by setting
`teamcity.github.bridge.checkName.stripPrefix`, which makes the warning worth
more than it was.

**Feasible.** The Check Run name is computed in one place
(`checkRunName` = `TeamCity / <buildType fullName>`), so the plugin knows
every name it will ever post. The other half is
`GET /repos/{o}/{r}/branches/{b}/protection/required_status_checks` (or the
rulesets API), which the App can read given repository administration read.

**Design.** A self-test row — the admin page already runs a battery of them
— listing required check names that no opted-in build configuration will
produce, and (informational) opted-in configurations that are not required.
Skipped, not failed, when the App lacks the permission to read protection.

**Effort.** Small, and it fits exactly where the plugin already differs from
a relay: it tells you when it is misconfigured.

## Say where the build is in the queue

**Problem.** The `queued` Check Run says "Queued" and nothing else. A
reviewer watching a pull request cannot tell "the agents are busy, this is
26th in line, four minutes out" from "this is stuck". TeamCity knows —
its queue page says exactly that — and the pull request is where people
are looking.

**Feasible.** `SQueuedBuild#getBuildEstimates` carries the position and the
estimated start; the queue page renders *"4m 12s to start: There are no idle
compatible agents which can run this build"* and *"26th position in queue"*
from it. The publisher already posts a `queued` Check Run, so this is a
richer summary on a request that is already being made.

**Design.** Summary line on the `queued` row: "26th in queue, ~4m to start
— no idle compatible agent". Re-posted when the estimate changes materially
would be noise; once, at enqueue, is enough.

**Effort.** Small. Watch out for estimates being absent (a build with
unresolved dependencies has none) and for the same "no compatible agent"
wording problem `agentWaitHint` already fights.

## Target one build configuration from a PR comment

**Problem.** The comment trigger is all-or-nothing: the phrase re-runs
every opted-in build configuration. On a matrix of eleven, a reviewer who
wants the one Windows leg re-run pays for eleven.

**Feasible.** The command path already resolves the phrase, the trusted
commenter and the build configurations (`PullRequestEventListener`), and
`COMMAND` builds already bypass the soft gates.

**Design.** `/rebuild Windows` — the argument matches build configuration
names (substring, case-insensitive) within the project, and matches nothing
means the whole set, as today. Echo what was matched in the reply so a typo
is visible rather than silent.

**Effort.** Small.

## Resolve a Check Run left `in_progress`

**Problem.** If the server stops between a build's start and its finish, or
the finish event is missed, the pull request keeps an `in_progress` row that
never resolves — and a required check that never resolves blocks the merge
until somebody re-runs it by hand. This is the one failure mode of the
whole design that a human has to clean up.

**Feasible.** On `serverStartup` the plugin can walk its own recent history
(the builds carrying the feature, finished within the last N hours) and
reconcile: any build that is finished in TeamCity but whose last published
Check Run was `queued` or `in_progress` gets its conclusion posted. The
publisher is idempotent — GitHub dedups on `(name, head_sha)` — so
re-posting a conclusion that already landed is harmless.

**Effort.** Medium. The care is in bounding the sweep and in not
resurrecting rows for commits that no longer belong to an open PR.

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
coverage — which today it rarely does. Kept for when that changes; not a
candidate for the next release.

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

## Not doing

Decided against, with the reason — so the next reader does not re-propose
them, and so a change of circumstances can be recognised as one.

| Idea | Why not |
|---|---|
| **Retry an infrastructure failure automatically** | It rests on the infra-versus-code classification being trustworthy enough to spend agents on, and in practice that line is too subtle to draw. The plugin *names* the suspected cause and lets a human decide (`checkRun.infraNeutral`); spending the operator's agents on that same guess is a step too far. |
| **Report flaky tests as flaky** | TeamCity already has a flaky-test detector. It works badly, and doing better without false positives is a research problem, not a plugin feature — and a false "this is flaky" is worse than no label at all: it tells a reviewer to ignore a real failure. |
| **The sticky PR summary comment** | Removed in 1.10.0, not shelved. It was refreshed by delete-then-post, so every build notified every watcher of the pull request — spam, whatever the content said. Editing in place needs `PATCH`, which `HttpURLConnection` refuses, so the fix was "replace the HTTP layer" for a feature nobody asked for. The Checks panel already carries the same information, including the artifact links. Removing it also brought the App's **pull requests** permission back down to **read**. |
| **End-to-end fixture against a real TeamCity** | `tests-support` pulls in a large part of TeamCity's server jar graph for ~30 s of startup per test class, to cover wiring that a single install on the sandbox covers faster. The lag bugs it would have caught were caught by installing it. Not worth the weight. |

If the flaky-test question comes back, the interesting version is not
"detect flakiness" but "surface what TeamCity already decided" — and that
inherits TeamCity's false positives, which is the objection above.

## Blocked on JetBrains

Re-check on each TeamCity release; nothing to do until then.

| Question | What we want | Status as of TC 2026.1 |
|---|---|---|
| A public `BuildBranchInfoProvider` | Override what the **Branch** column displays. Largely moot since `prBuildRef=branch` makes the column show the real branch name; it would only help projects staying on the `pull/N` model. | Absent. `BranchDisplayNameProvider` too; `Branch.getDisplayName()` is read-only and `setDesiredBranchName()` rewrites the ref itself, not its display. |
| An **inline** place for an outbound link on a build | A link to the pull request on the build page itself, not one tab away. | Settled well enough to close: the *Pull request* **build tab** shipped in 1.10.0 (`BUILD_RESULTS_TAB` renders on the current UI). The inline routes remain dead — a tag **is** a filter and the React pages bind that by delegation on an ancestor (its capture listener wins), `PlaceId.BUILD_SUMMARY` / `BUILD_ACTIONS` render on the **classic** build page only, and a client-side overlay goes stale on a single-page app. Re-check only if the SDK gains a Sakura-aware place. |
| `ConnectionCredentialsFactory` for GitHub App | Token acquisition that does not need our own JWT self-mint path. | Unsupported (`Unsupported Connection Provider type: GitHubApp`). Worked around by self-minting. If it lands, the self-mint primary path can go and the credentials-manager fallback suffices again. |

## Recording a new idea

Open a GitHub issue with the `enhancement` label, sketch the plan there,
then mirror it here as a section — value first, with what makes it
feasible. This file stays the single answer to "what's next".
