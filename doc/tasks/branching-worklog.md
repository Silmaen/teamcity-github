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
| 2026-07-28 | Backlog order | `G11 → G19 → G18 → G13 → G12/G12b → G14`. | — |

**Still open:** the pre-PR build policy (R7 / F1 — on demand vs automatic on
push). Largely absorbed by G18: with branch-source builds the PR build *is*
the branch build, so "automatic" no longer costs a second build.

---

## Gap ledger

Status values: `open` · `in progress` · **`done (x.y.z)`** · `dropped` ·
`superseded`.

| # | Gap | Status | Where |
|---|---|---|---|
| G11 | Command-triggered builds are killed by the queue cleaner's soft gates | open | `BridgeGate`, `DraftBuildQueueCleaner`, `BuildStatusCheckRunPublisher`, `PullRequestEventListener` |
| G19 | Fork PRs are not recognised (`head.repo.full_name` never parsed) | open | `WebhookPayloadParser`, `PullRequestEventListener` |
| G18 | No branch-source mode (PR builds always on `pull/N`) | open | `BridgeFeatureConfig`, `PullRequestEventListener`, gate call sites |
| G13 | No `check_suite.rerequested` handling (re-run all / only failed) | open | `WebhookPayloadParser`, `PluginWebhookController`, `PullRequestEventListener` |
| G12 | No unified branch/PR view searchable by either key | open | new web page |
| G12b | No retro-association of pre-PR builds | open | `PullRequestEventListener` |
| G14 | Check Runs and PR comment carry no artifact links | open | `BuildStatusCheckRunPublisher`, `PrSummaryCommenter` |
| G17 | A red long-life build cannot name the merged PR / author | open | — |
| G1 | No `pull_request.labeled` / `unlabeled` handling | open | — |
| G3 | No `pull_request.edited` handling | open | — |
| G4 | No `pull_request.reopened` handling | open | — |
| G15 | Nothing warns when the bundled `commitStatusPublisher` is also active | open | — |
| G16 | "Build the branch only while it has no PR" | superseded by G18 | — |
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
| G11 | ☐ | ☐ | ☐ | n/a | ☐ |
| G19 | ☐ | ☐ | ☐ | n/a | ☐ |
| G18 | ☐ | ☐ | ☐ | ☐ | ☐ |
| G13 | ☐ | ☐ | ☐ | n/a | ☐ |
| G12 / G12b | ☐ | ☐ | ☐ | n/a | ☐ |
| G14 | ☐ | ☐ | ☐ | n/a | ☐ |
| G10 (R10 warning) | ☑ | ☑ | ☑ | ☑ | n/a — warning not shipped yet (G15) |

---

## Version staging

Per the project convention, development happens on the next **patch**
version and is promoted to a minor when the batch is released:

- `1.8.2` — last released state before this batch.
- `1.8.3` — staging version for the G11 → G14 batch (this branch).
- `1.9.0` — the release that publishes it.
