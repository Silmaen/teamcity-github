# Working log — branching requirements, decisions and gap ledger

**This is the living document.** [branching-workflows.md](../branching-workflows.md)
describes the *target* model (rules R1–R19, scenarios F1–F28, gaps G1–G19);
this page records **what was decided, when, and what is actually in the
code**, so the two never drift.

Keep it updated in the same commit as the code. The rule of thumb:

| When you… | Update here | Update there |
|---|---|---|
| get an answer from the team | § Decision log (new row, dated) | the affected rule/scenario in `branching-workflows.md` |
| close a gap | § Gap ledger (status, version, files, tests) | remove the "not implemented" caveat from the affected scenario |
| ship user-visible behaviour | § Doc-sync checklist (tick the boxes) | `configuration.md`, `usage-scenarios.md`, `quickstart.md`, `troubleshooting.md`, `CHANGELOG.md` |

---

## Decision log

Answers from the team, newest last. `branching-workflows.md` §0 turns these
into rules; §9 keeps the short audit trail.

| Date | Question | Answer | Rule |
|---|---|---|---|
| 2026-07-28 | Branch naming | `master` for this project; the plugin stays generic (`<default branch>`, `<long-life branches>`, `<work branches>`). Release branches are `Release/YY.MM`. | R1, R2 |
| 2026-07-28 | Work-branch convention | `Feature/*`, `Bugfix/*`, `Experiment/*`, capitalised, and **nothing else** — any other name is forbidden by GitHub. | R3, R8 |
| 2026-07-28 | Release branch lifetime | A `Release/*` **survives its cascade merges** and keeps taking fixes until retired; work branches die at their merge. | R2, R3 |
| 2026-07-28 | Who cascades | A **human**, because conflicts are frequent and must be resolved by a person. | R13 |
| 2026-07-28 | Required checks | **One single set** for this project; other deployments may differ. | R14 |
| 2026-07-28 | QA status | QA is a **reviewer**, not a gate: no formal GitHub status, findings tracked in an external bug tracker. | R15 |
| 2026-07-28 | QA tooling | None to integrate — the deployment is a TeamCity build. | R15 |
| 2026-07-28 | QA hand-off | QA wants **a reference to a set of builds** (and artefacts), reachable from GitHub; they have GitHub access. | R15 |
| 2026-07-28 | Red long-life branch | The **PR author** investigates. | R16 |
| 2026-07-28 | CI cost | No hard ceiling, but the per-PR task set is deliberately limited. | R17 |
| 2026-07-28 | `Experiment/*` branches | Trigger **nothing** automatically, must stay **manually startable** — with or without a PR. | R6 |
| 2026-07-28 | `Feature/*` / `Bugfix/*` without PR | Must be **buildable before any PR exists**. | R7 |
| 2026-07-28 | Long-life branches | Some pipelines on **push**, others **scheduled**; both on the same branch. | R11 |
| 2026-07-28 | Branch ↔ PR view | **One unified view**, searchable by **branch name or PR number**, with **retro-association**. | R12 |
| 2026-07-28 | Forks | **Ignored by default, as plugin behaviour** — the bridge is attached to one repository, never its forks. | R9 |
| 2026-07-28 | Merge preview | Never used: builds validate the **source branch**, not `refs/pull/*/merge`. | R18 |
| 2026-07-28 | `pull/N` refs | Replace them with **branch-source builds** (per-project switch); rewriting TeamCity's Branch column is cosmetic and dropped. | R19 |
| 2026-07-28 | Bundled `commitStatusPublisher` | The plugin **warns only**, never disables anything — correct configuration is the user's responsibility, and it must be written in the user docs. | R10 |
| 2026-07-29 | Two kinds of TC task | **Publication** is a per-build-configuration switch (`publishChecks`) and depends on **nothing else** — not on the trigger source. **Triggering** is separate: the `triggerOn*` flags say what the bridge starts automatically, and the bridge never removes a build it did not start. | — |
| 2026-07-29 | Draft skip | Applies to the automatic path only; an explicit Run or command on a draft runs and reports. Settles the 1.3.0-vs-1.5.0 divergence in favour of "no silence after an explicit request". | — |
| 2026-07-29 | Reuse a passed commit | Opt-in per build configuration (`skipIfCommitPassed`): an **automatic** build of a commit that already passed is removed and the success republished. Keyed on the **commit alone**, any ref. A manual Run and the Re-run buttons always re-run; scheduled suites leave it off. | — |
| 2026-07-29 | Queue cleanup scope | Restricted to build configurations carrying the **GitHub Bridge** build feature (the preferred option over a project-level scope) — already the case, now a documented invariant in `QueueCleanupPolicy`. Plus a server-wide master switch `queueCleanup.enabled` on the admin page. | — |
| 2026-07-28 | Backlog order | `G11 → G19 → G18 → G13 → G12/G12b → G14`. | — |

**Still open:** the pre-PR build policy (R7 / F1 — on demand vs automatic on
push). Largely absorbed by G18: with branch-source builds the PR build *is*
the branch build, so "automatic" no longer costs a second build.

---

## Gap ledger

Status values: `open` · `in progress` · **`done (x.y.z)`** · `dropped` ·
`superseded`.

| # | Gap | Status | What it is / what shipped | Where in the code |
|---|---|---|---|---|
| G11 | On-demand-only build configurations | **done (1.8.3)** | The command paths (comment, approval, re-run, API) skipped the gate at enqueue, but the queue cleaner re-applied it with `isManual=false` and removed the build — so any filter keeping a build configuration off the automatic path also killed its on-demand build. Commands are now stamped and gated like a manual Run: HARD blocks apply, SOFT ones (branch list, PR metadata) do not. | `BridgeTrigger` + `BridgeTriggerMarker` (new), `BridgeGate.decide(trigger=…)`, `GateContextResolver` (new), cleaner / start-precondition / publisher, listener enqueue paths |
| G19 | Fork PRs not recognised | **done (1.8.3)** | `head.repo.full_name` was never parsed, so a fork PR was indistinguishable from a local one. A foreign head is now logged, counted (`fork_events_ignored`) and dropped; a blank head repo (deleted fork) fails open. Prerequisite for G18. | `WebhookPayloadParser`, `GitHubClient.parsePrInfo`, `PrInfo.headRepo`, `PullRequestEventListener.isFork` |
| G18 | Branch-source PR builds | **done (1.8.3)** | PR builds always ran on the synthetic `pull/N`: unreadable branch names, and two builds per commit once a PR existed. New per-project `prBuildRef = pull \| branch` enqueues on the PR head ref; PR-ness comes from the commit, not the ref name. Subsumes G16. | `PrBuildRef`, project param + page checkbox, `prBuildRefFor`, `resolvesPrFromCommit`, `PrPromotionTagger` |
| G13 | `check_suite.rerequested` | **done (1.8.3)** | "Re-run all checks" was answered *204 unsupported*. It now re-runs every opted-in build configuration at that head, with `rerunAll.onlyFailed` to restrict it to the failed ones. The managed App subscribes to `check_suite`. | `parseCheckSuiteRerequest`, controller route, `handleRerunAll`, `AppManager.REQUIRED_EVENTS` |
| G12 | Unified searchable branch/PR view | **done (1.8.3)** | The branch↔PR association existed in the data but nowhere to see it, and TeamCity's Branch column has no public override hook. Project tab **Branches & PRs**: one list carrying both keys, searchable by branch name or PR number, sortable by time/branch/PR. | `BridgeBuildsTab` + `project/bridgeBuilds.jsp`, PR build tag (`prTag.enabled` / `prTag.prefix`) |
| G12b | Retro-association of pre-PR builds | **done (1.8.3)** | A build that ran on a work branch before its PR existed had no PR link. `pull_request.opened` / `synchronize` back-fill the PR and draft/ready tags on builds already at that head, without an extra API call. | `PullRequestEventListener.retroAssociate` |
| G14 | Artifact links | **done (1.8.3)** | The PR linked to build pages but not to what they produced. The completed Check Run lists top-level artifacts and the sticky comment gains an `[artifacts]` cell (`checkRun.artifactLinks`, on by default). This is the QA hand-off of F26. | `artifactSection` / `joinSections`, `PrSummaryCommenter.Row.artifactsUrl` |
| G16 | "Build the branch only while it has no PR" | **superseded** | G18 shipped: there is no second ref left to deduplicate. | — |
| G6 | Post an arbitrary Check Run from outside | **dropped** | R15: no external QA tooling — the deployment is a TeamCity build. Revisit only if a non-TeamCity system must report. | — |
| G17 | Name the merged PR on a red long-life build | open | R16 makes the PR author responsible, but nothing links a merge-commit build back to its PR: `branchPrLookup` matches only *open* PRs whose head is the commit (F9). Ask GitHub for the PR associated with the merge commit — the same endpoint returns merged PRs — then publish number + author as build parameters, and optionally comment on the merged PR. Small. | — |
| G15 | Warn on a double status publisher | open | R10 / F24 is documented for users but nothing detects it. One `WARN` per build configuration per server start + a self-test row, read from `resolvedSettings` so template-inherited publishers are caught. **Warn only, never act** (roadmap Item 4). Small. | — |
| G1 | `pull_request.labeled` / `unlabeled` | open | Labels are a *filter*, never a *trigger*: adding `ci-full` does nothing until the next push/comment/approval (F5). Handle `labeled` and re-evaluate candidates. Small. | — |
| G3 | `pull_request.edited` | open | Editing the title to add/remove `[skip ci]` or a require-phrase has no effect until the next event. Same shape as G1. Small. | — |
| G4 | `pull_request.reopened` | open | A reopened PR gets no fresh build until the next push. Add the action to `PrAction`. Small. | — |
| G10 | Line-level Check Run annotations | open | Failures link out to TeamCity instead of annotating the diff. Roadmap Item 10 (partial: `output.text` already carries the failure reasons). Medium. | — |
| G2 | Base/target-branch filter | open — not needed here | "This suite only for PRs targeting `Release/*`" is not expressible. R14 (one single required set) makes it unnecessary for this project; kept for deployments that gate release PRs differently. | — |
| G5 | Comment triggers need a trusted `author_association` | open — moot here | R13 makes the cascade human-driven, so no bot needs it. Still what limits a read-only QA account (F16). | — |
| G7 | Running builds are never cancelled | open — by design | Superseded PR builds keep burning agents (F4). Deliberate: stopping a running build has surprising side effects. Revisit if agent cost bites. | — |
| G8 | `push` subscribed but ignored | open | Deliveries answered `204 unsupported event`; harmless but confusing in the recent-events log. `check_suite` is now handled (G13); for `push`, either handle it or drop it from `WebhookInfo`. Small. | — |
| G9 | Merge-queue (`merge_group`) support | open — only if adopted | If GitHub merge queues are ever enabled on the protected branches, checks would not run on the queue's temporary refs. Medium. | — |

---|---|---|---|
| G11 | Command-triggered builds are killed by the queue cleaner's soft gates | **done (1.8.3)** | `BridgeTrigger` (new), `BridgeGate.decide(trigger=…)`, `GateContextResolver` (new), cleaner / start-precondition / publisher, listener enqueue paths |
| G19 | Fork PRs are not recognised (`head.repo.full_name` never parsed) | **done (1.8.3)** | `WebhookPayloadParser`, `GitHubClient.parsePrInfo`, `PrInfo.headRepo`, `PullRequestEventListener.isFork`, metric `fork_events_ignored` |
| G18 | No branch-source mode (PR builds always on `pull/N`) | **done (1.8.3)** | `PrBuildRef` + project param `prBuildRef`, project page checkbox, `prBuildRefFor`, `resolvesPrFromCommit`, `PrPromotionTagger` |
| G13 | No `check_suite.rerequested` handling (re-run all / only failed) | **done (1.8.3)** | `parseCheckSuiteRerequest`, controller route, `handleRerunAll`, setting `rerunAll.onlyFailed`, App subscribes to `check_suite` |
| G12 | No unified branch/PR view searchable by either key | **done (1.8.3)** | `BridgeBuildsTab` + `project/bridgeBuilds.jsp`, PR build tag (`prTag.enabled` / `prTag.prefix`) |
| G12b | No retro-association of pre-PR builds | **done (1.8.3)** | `PullRequestEventListener.retroAssociate` on opened/synchronize |
| G14 | Check Runs and PR comment carry no artifact links | **done (1.8.3)** | `artifactSection` / `joinSections`, `PrSummaryCommenter.Row.artifactsUrl`, setting `checkRun.artifactLinks` |
| G17 | A red long-life build cannot name the merged PR / author | open | — |
| G1 | No `pull_request.labeled` / `unlabeled` handling | open | — |
| G3 | No `pull_request.edited` handling | open | — |
| G4 | No `pull_request.reopened` handling | open | — |
| G15 | Nothing warns when the bundled `commitStatusPublisher` is also active | open | — |
| G16 | "Build the branch only while it has no PR" | **superseded** — G18 shipped, there is no second ref left to deduplicate | — |
| G2 | No base/target-branch filter | open — not needed here (R14) | — |
| G5 | Comment triggers need a trusted `author_association` | open — moot here (R13) | — |
| G6 | Post an arbitrary Check Run from outside | dropped (R15) | — |
| G7 | Running builds are never cancelled | open — deliberate design choice | — |
| G8 | `push` / `check_suite` subscribed but ignored | open — `check_suite` half covered by G13 | — |
| G9 | No merge-queue (`merge_group`) support | open — only if merge queues are adopted | — |
| G10 | No line-level Check Run annotations | open — roadmap Item 10 | — |

---

## Doc-sync checklist

Tick when the shipped behaviour is reflected. A gap is not "done" until its
row here is complete.

| Gap | `branching-workflows` | `configuration` | `usage-scenarios` | `quickstart` / `troubleshooting` | `CHANGELOG` |
|---|---|---|---|---|---|
| G11 | ☑ | ☑ (published `triggerSource` param) | ☑ (scenario 23) | n/a | ☑ |
| G19 | ☑ | ☑ (*Forks are out of scope*) | ☑ (summary table) | n/a | ☑ |
| G18 | ☑ | ☑ (*Branch-source PR builds* + project param) | ☑ (scenario 22) | n/a — project-level opt-in | ☑ |
| G13 | ☑ | ☑ (`rerunAll.onlyFailed`) | ☑ (scenario 23) | n/a | ☑ |
| G12 / G12b | ☑ | ☐ — the tab is self-explanatory, add a section if operators ask | ☑ (summary table) | n/a | ☑ |
| G14 | ☑ | ☑ (`checkRun.artifactLinks`) | ☑ (summary table) | n/a | ☑ |
| G10 (R10 warning) | ☑ | ☑ | ☑ | ☑ | n/a — warning not shipped yet (G15) |

---

## Next up

The batch above closed the agreed sequence. What remains, in the order the
scenarios argue for:

1. **G17** — name the merged PR (and its author) on a red `<long-life branch>`
   build, so R16 has tooling behind it. Small: ask GitHub for the PR
   associated with the merge commit, publish number + author as build
   parameters.
2. **G15** — warn when a build configuration carries both the bridge and the
   bundled `commitStatusPublisher` (R10 is documented, not yet detected).
3. **G1 / G3 / G4** — react to `labeled`, `edited`, `reopened`, so a label is
   a trigger and not only a filter.
4. **G10** — line-level Check Run annotations.

Still open as a *decision*: the pre-PR build policy of F1 — but with G18
shipped, "automatic on push" no longer costs a second build, so the
question is now mostly moot.

## Version staging

Per the project convention, development happens on the next **patch**
version and is promoted to a minor when the batch is released:

- `1.8.2` — last released state before this batch.
- `1.8.3` — staging version for the G11 → G14 batch (this branch).
- `1.9.0` — the release that publishes it.
