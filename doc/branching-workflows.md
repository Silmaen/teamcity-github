# Branching workflows: how code travels between GitHub and TeamCity

A worked example of the bridge in a real branching model: which pipelines
fire, when, and what GitHub shows. Where
[usage-scenarios.md](usage-scenarios.md) answers *"this webhook arrives, what
does the plugin do?"*, this page answers *"a change moves through our
branches — what happens?"*

Each scenario is written as **trigger → TeamCity → GitHub feedback →
configuration**. Scenarios are numbered `F1…F28` (`F` for *flow*) so they
never collide with the plugin-mechanics scenarios 1–29 of
`usage-scenarios.md`.

> **Read this as an example, not as a specification of the plugin.** It
> describes **one deployment** — a closed branch namespace, one required-check
> set, no bundled status publisher — and those are that team's constraints,
> not the plugin's. Two of them the plugin does impose, and they are called
> out where they apply: pull requests from **forks are ignored**, and
> **branch-source builds** are a per-project switch.
---

## 0. The branch model this page assumes

```mermaid
%%{init: {'gitGraph': {'mainBranchName': 'master'}}}%%
gitGraph
    commit id: "history"
    branch Release/26.03 order: 2
    commit id: "26.03 cut"
    checkout master
    commit id: "other PR #41"
    commit id: "other PR #45"
    branch Release/26.06 order: 1
    commit id: "26.06 cut"
    checkout master
    commit id: "other PR #52"
    checkout Release/26.03
    branch Bugfix/z order: 3
    commit id: "hotfix"
    checkout master
    commit id: "other PR #58"
    checkout Release/26.03
    merge Bugfix/z tag: "PR to Release/26.03"
    checkout master
    commit id: "other PR #63"
    checkout Release/26.06
    merge Release/26.03 tag: "cascade PR"
    checkout master
    commit id: "other PR #71"
    merge Release/26.06 tag: "cascade PR"
    commit id: "other PR #74"
    branch Feature/x order: 4
    commit id: "feature work"
    checkout Release/26.03
    commit id: "26.03 lives on"
    checkout Release/26.06
    commit id: "26.06 lives on"
    checkout master
    commit id: "other PR #77"
    merge Feature/x tag: "PR to master"
    commit id: "other PR #80"
    branch Experiment/spike order: 5
    commit id: "spike: manual runs only"
```

Reading the diagram:

- The `other PR #nn` dots on `master` stand for the other PRs merged in the
  meantime, whose branches are not drawn.
- `Release/*` branches **keep living after a cascade merge** (the "lives
  on" commits): they go on taking fixes and are cascaded again the
  following week, for the whole supported life of the release.
- `Feature/*` and `Bugfix/*` branches, by contrast, **die at their merge**
  and are deleted.
- `Experiment/spike` never merges and never triggers anything: it is the
  R6 case, detailed in F1b.

### Vocabulary: the generic model, and this project's instance

The rules below are written in **generic terms**, because the plugin must
serve any team, not just this one. The right-hand column is what those
terms mean *for this project*.

| Generic term (used throughout this page) | This project | How the plugin sees it |
|---|---|---|
| **`<default branch>`** | `master` | whatever GitHub reports as the repository default — the plugin hardcodes nothing, and `main` vs `master` is never a code path |
| **`<long-life branches>`** | `<default branch>` + `Release/YY.MM` (e.g. `Release/26.06`) | the set matched by `branchTrigger.branches`; long-lived, push-protected, no PR of their own |
| **`<work branches>`** | `Feature/*`, `Bugfix/*`, `Experiment/*` — **and nothing else** (R8) | the head ref of a PR, and/or a ref built directly by name. Today a PR build runs on the synthetic `pull/N` ref; branch-source mode (F28) uses the head ref itself |

Note the capitalised prefixes: Git refs are case-sensitive, so every branch
spec and filter in this page uses that exact casing.

Rules taken as given:

| # | Rule |
|---|---|
| R1 | One **`<default branch>`** per repository — `master` here. The plugin stays generic: nothing below depends on its name. |
| R2 | Dated **`<long-life branches>`** `Release/YY.MM`. Several may be alive at once, and each **survives its cascade merges** — it keeps taking fixes until the release is retired. |
| R3 | **`<work branches>`** are merged into `<default branch>` **or** into a `Release/*` branch — always through a PR — and are **short-lived**: merged, then deleted. |
| R4 | At least weekly, a **cascade merge** propagates each modified `Release/*` up to the next release, and finally into `<default branch>`. |
| R5 | Every `<long-life branch>` is **push-protected**: no direct push, merges land through PRs only. |
| R6 | **`Experiment/*`** branches trigger **nothing** automatically, but must remain **manually startable** in TeamCity — with or without an associated PR. |
| R7 | `Feature/*` and `Bugfix/*` branches must be **buildable before any PR exists** — a developer wants feedback on a plain branch, PR or no PR. |
| R8 | The branch namespace is **closed**: GitHub forbids any name outside `<default branch>`, `Release/*`, `Feature/*`, `Bugfix/*`, `Experiment/*`. Branch specs can therefore be exhaustive, and a catch-all exclusion is safe. |
| R9 | **Forks are out of scope — by default, and as plugin behaviour.** The bridge is attached to **one repository**, never to its forks: a PR whose head lives in another repository must be ignored outright. Not a deployment convention, a plugin default (F23). |
| R10 | When the bridge is active on a build configuration, TeamCity's **bundled `commitStatusPublisher` must be off** on it. One status producer, never two. The plugin **warns only** — configuring builds correctly is the operator's job (F24). |
| R11 | On `<long-life branches>`, some pipelines run **on push**, others on a **schedule** (nightly / weekly). Both must coexist on the same branch. |
| R12 | A build on a `<work branch>` must be **automatically associated with its PR** when one exists (GitHub allows only one open PR per head branch, so existence is the whole question), including **retro-actively** for builds that ran before the PR was opened. Not needed on `<long-life branches>`. |
| R13 | The **cascade PRs are opened and merged by a human** (R4), because conflicts are frequent and a human must resolve them. |
| R14 | **One single set of required checks** for this project — no per-target-branch variation. Other deployments may differ. |
| R15 | **QA is a reviewer, not a gate.** QA has no formal status in GitHub today; findings are tracked in an external bug tracker. What QA needs from us is a **reference to a set of builds** (and their artefacts), not a blocking check. |
| R16 | What we validate is the **source branch**, not GitHub's merge preview. The merge-preview ref (`refs/pull/*/merge`) has never been used here — builds have always been of the branch as it stands. |
| R17 | Consequently the model of choice is **branch-source builds**: the bridge builds and reports on the real branch ref (`Feature/toto`), not on a synthetic `pull/N` ref (F28). |

Consequences that shape everything below:

- **Every** line of code crosses a PR at least once, so the **PR is the
  primary integration point**; `<long-life branches>` only ever see
  *post-merge* commits. (Which *ref* carries a PR build — `pull/N` today,
  the head ref after F28 — changes nothing here.)
- Because pushes are protected, a **red post-merge build on a
  `<long-life branch>` cannot be fixed by a push** — it needs another PR.
  Post-merge builds are therefore *alarms*, not gates; the gates live on the
  PR. Routing that alarm to whoever caused it is **TeamCity's** job, not the
  bridge's: investigation auto-assignment already does it (see F9).
- The cascade (R4/R13) is **human-driven**: PRs between two protected
  branches, opened and merged by a person. That is good news for us — the
  author is a normal team member, so comment triggers, approvals and
  labels all behave exactly as on a human PR, and the bot-association
  problem does not arise here. It also means conflict resolution
  (F14) is a *normal* part of the weekly flow, not an edge case.
- Experimental branches (R6) need **"reachable but silent"**: known to
  TeamCity (so a human can pick them in the Run dialog) yet excluded from
  every automatic trigger. That is a *soft* exclusion — see F1b, and note
  the `triggerOnBranch=off` trap described there.
- R7 + R12 together mean the interesting unit is **the commit, not the
  ref**: the same commit may be built from `Feature/x` (pre-PR) and from
  `pull/N` (post-PR), and both must report to the same place. That is what
  the `branchPrLookup` setting already does (F25) — and branch-source mode removes the
  duplication altogether by never creating the second ref.
- R8 (closed namespace) is what makes every branch spec in this page
  trustworthy: there is no "other" branch shape to defend against, so
  filters can be written as explicit include lists.
- R9 (forks ignored) removes the whole untrusted-contributor dimension:
  head refs are always local branch names, tokens always cover the head
  repo, and `author_association` is never that of an outsider. It is also
  what makes branch-source mode possible at all — a fork's head ref does not
  exist locally.
  See F23.
- R15 (QA as reviewer) changes what "QA support" means: not a required
  Check Run, but **discoverability** — from GitHub, reach the set of builds
  and artefacts for a ref. See F17 and F26, which were rewritten around
  this answer.

### Who actually starts a build

The single most useful thing to internalise: **the bridge only ever
starts PR builds.** Branch builds are started by TeamCity's own triggers
(VCS, schedule) or by a human; the bridge merely *gates* them and
*reports* them.

The diagram below shows the default model, where a PR build runs on the
`pull/N` ref. In branch-source mode (F28) the shape is unchanged — the bridge
still starts only PR builds — but it enqueues them on the **head ref**, and
"is this a PR context?" comes from the commit → PR lookup instead of the ref
name. Read every `pull/N` below as "the ref carrying the PR build".

```mermaid
flowchart TD
    A[New commit pushed] --> B{Open PR for this commit?}

    B -->|yes| C["Bridge listener reacts to<br/>pull_request opened / ready_for_review / synchronize"]
    C --> D{"BridgeGate.decide, isManual=false"}
    D -->|ALLOW| E["Build enqueued by the bridge<br/>Check Runs on the PR"]
    D -->|SUPPRESS_DRAFT / BRANCH_PR / METADATA| F["Nothing enqueued<br/>Skipped Check Run posted"]
    D -->|SUPPRESS_HARD| G["Nothing enqueued<br/>silent on GitHub"]

    B -->|no| H{"Branch matched by a TC<br/>VCS or schedule trigger?"}
    H -->|yes| I["TeamCity enqueues<br/>the bridge never starts branch builds"]
    I --> J{"BridgeGate.decide, isManual=false"}
    J -->|ALLOW| K["Build runs<br/>Check Run on the commit"]
    J -->|SUPPRESS_BRANCH_NON_PR| L["Queued build removed<br/>silent on GitHub"]
    J -->|SUPPRESS_HARD| M2["Left alone, but not reported"]
    H -->|no| M["Nothing happens.<br/>A human can still click Run — see F1b"]

    M --> N{"Manual Run in the TC UI"}
    N --> O{"BridgeGate.decide, isManual=true"}
    O -->|"ALLOW: soft filters bypassed"| K
    O -->|"project-level mute"| M2
```

---

## 1. Pipeline archetypes

Before the scenarios, the vocabulary. Each archetype is one TeamCity
build configuration (BT) carrying the **GitHub Bridge integration**
build feature, with its own gates. Check Run names appear on GitHub as
`TeamCity / <BT full name>`.

| Archetype | Runs on | Cost | Gate (plugin config) | Required in branch protection? |
|---|---|---|---|---|
| **A1 — PR fast checks** (compile + lint + unit) | PR ref (`pull/N`, or the head branch in branch-source mode), drafts included | low | `triggerOnPrReady=on`, `triggerOnPrDraft=on` | yes |
| **A2 — PR full suite** (integration, multi-platform) | PR ref, ready only | high | `triggerOnPrDraft=off` | yes |
| **A3 — PR heavy/opt-in suite** (perf, long soak, big matrix) | PR ref, on demand | very high | `labelFilter=+:ci-full` and/or `runOnApproval=true` and/or `commentTrigger=/full` | no (informational) |
| **A4 — Post-merge CI on default branch** (on push) | `<default branch>` (`master` here) | medium | TC **VCS trigger** + `triggerOnBranch=on`, `branchTrigger.branches=+:<default branch>` | n/a (no PR) |
| **A5 — Post-merge CI on release branches** (on push) | `Release/*` | medium | TC **VCS trigger** + `branchTrigger.branches=+:Release/*` | n/a |
| **A6 — Nightly / weekly suites** (R11) | default + all live `Release/*` | high | TC **schedule trigger** + its own branch filter; distinct BT name so the Check Run never collides with A4/A5 | n/a |
| **A7 — Release candidate packaging** | `Release/*` (tag or manual) | medium | manual / scheduled; bridge for the Check Run only | n/a |
| **A8 — QA environment deploy** | `Release/*`, sometimes `pull/N` | medium | manual, `commentTrigger=/deploy-qa`, or external API | no |
| **A9 — Manual-test sign-off** (records a human verdict) | PR ref or `Release/*` | ~0 | `commentTrigger=/qa-ok`, or manual run, or external API | **no** — R15: QA is a reviewer, not a gate. Kept as a reference pattern only (F17) |
| **A10 — Cascade merge validation** | `pull/N` of a cascade PR | medium | same as A1+A2; see F13 | yes |
| **A11 — Experimental / on-demand branch build** | `Experiment/*`, **manual only** | any | in the VCS branch spec, **not** in `branchTrigger.branches`, `triggerOnBranch` left **on**, no VCS trigger matching it | no |
| **A12 — Pre-PR work-branch build** (R7) | `Feature/*`, `Bugfix/*`, no PR yet | low–medium | same shape as A11, plus (optionally) a VCS trigger if pre-PR builds should be automatic; `branchPrLookup` attaches it to the PR once one exists. **In branch-source mode this archetype merges into A1/A2** — same ref, same build | no |

> **Your call:** which of these actually exist in your TeamCity
> project tree, which are one BT vs a build chain, and which ones make up
> the single required-check set of R14.

---

## 2. Development phase

### F1 — `Feature/*` / `Bugfix/*` branch with no PR yet (R7)

**Actor:** a developer pushes `Feature/x` and wants a build **before**
opening any PR.

**What must be true:** the branch is in the VCS root's branch spec
(`+:Feature/*`, `+:Bugfix/*`) so it is selectable in TeamCity, and
`triggerOnBranch` stays **on** on the build configurations (the
`triggerOnBranch=off` trap of F1b applies identically here — "off" kills
manual runs too).

**Two policies to choose between — this is a real decision, not a detail:**

| Policy | How | Cost | Consequence |
|---|---|---|---|
| **On demand** (recommended default) | no VCS trigger on `Feature/*`; `branchTrigger.branches` excludes them; a human clicks Run (or an external tool calls `POST /api/trigger`) | one build per explicit request | same setup as A11/A12; nothing is wasted, but there is no automatic pre-PR signal |
| **Automatic on push** | add a VCS trigger and put `+:Feature/*` / `+:Bugfix/*` in `branchTrigger.branches` | **two builds per commit** once a PR exists (one on `Feature/x`, one on `pull/N`) | needs a deduplication story, see the caution below |

**⚠️ The double-build effect.** With the automatic policy, after the PR is
opened every push produces a branch build *and* a PR build of the same
code. The bridge's smart-skip only deduplicates within the `pull/N` ref,
so it will **not** collapse the two. Both post a Check Run at the same
commit SHA under the **same name** (`TeamCity / <BT>`), so they overwrite
each other's row on GitHub — last writer wins, and the row may flip
between the two builds' results.

**Branch-source mode removes the effect (F28):** with PR builds
running on the head ref there is no second ref and no second build, so the
automatic policy is free. In `pull` mode the effect still applies — mitigate
by keeping the on-demand policy or by using a distinct build configuration
for pre-PR builds.

**GitHub feedback:** with `branchPrLookup` on, a pre-PR build still gets
its Check Run at the commit; the moment a PR exists for that head, the
same row is visible in the PR's Checks tab (F25).

**Your call:** which policy per BT? A cheap A1-style suite is a good
candidate for automatic pre-PR builds; A2 is not.

### F1b — Experimental branch: nothing automatic, manual run always possible (R6)

**Actor:** a developer exploring a spike on `Experiment/raytracing`. They want
**zero** automatic builds, but the ability to launch any pipeline by hand
— sometimes with a PR open for review, sometimes with no PR at all.

**The configuration that achieves it:**

| Layer | Setting | Why |
|---|---|---|
| VCS root | branch spec **includes** `+:Experiment/*` | otherwise TeamCity doesn't know the branch and it cannot be selected in the Run dialog |
| TC triggers | no VCS/schedule trigger matching `Experiment/*` (or excluded in the trigger's branch filter) | this is what actually prevents automatic branch builds |
| Bridge, project | `branchTrigger.branches` = `+:<default>` + `+:Release/*` (no `Experiment/*`) | the bridge gate skips automatic `Experiment/*` builds |
| Bridge, project | `prTrigger.branches` = `-:Experiment/*` (or a BT-level override) | prevents automatic PR builds when an experimental branch *does* get a PR |
| Bridge, BT | either flag setting works | neither can block a manual Run — see below |

**How the flags behave.** Both spellings give you the same thing, which is
what R6 asks for:

- `triggerOnBranch` **off** — the bridge never triggers this build
  configuration on a branch, and never removes such a build either. A Run, a
  schedule or a VCS trigger goes through and reports.
- `triggerOnBranch` **on** with `Experiment/*` outside `branchTrigger.branches`
  — the automatic path skips it, an explicit Run reports.

> Before 1.9.0 the first spelling was a trap: `triggerOnBranch=off` also
> **removed a manually started build from the queue**, silently. If you run an
> older version, use the second spelling.

```mermaid
flowchart TD
    A["Push on Experiment/raytracing"] --> B["No VCS trigger matches<br/>no PR event that the gate allows"]
    B --> C["Nothing is enqueued<br/>nothing posted on GitHub"]
    C --> D["Developer clicks Run in TeamCity<br/>on the Experiment/raytracing branch"]
    D --> E["The gate sees an explicit trigger:<br/>soft filters bypassed, nothing removed"]
    E --> F{"An open PR whose head<br/>is this commit?"}
    F -->|no| G["Check Run posted on the commit only"]
    F -->|yes| H["Check Run visible on the PR too;<br/>PR params + draft/ready tag<br/>via branchPrLookup"]
```

**GitHub feedback:** a manual `Experiment/*` build still publishes its Check Run
at the built commit. With the `branchPrLookup.enabled` server flag (the
"attach branch builds to their PR" setting), a build launched on the plain
branch ref also resolves the open PR whose head is that commit, so it
gets the PR parameters, the `draft`/`ready` tag and the summary comment —
i.e. a manual experimental build looks the same whether it was started
from `Experiment/raytracing` or from `pull/N`.

**Your calls:**
- The whole setup hinges on one glob, so `Experiment/*` must be a
  convention nobody bypasses: a spike branch named anything else falls
  into F1 (invisible to TeamCity) or, worse, into whatever `Feature/*`
  rules apply. Worth enforcing with a GitHub ruleset on branch names?
- If an experimental branch gets a PR (for review, not for merge), the
  automatic path posts **"Skipped: branch out of scope"** on every PR event.
  Acceptable noise, or should those PRs stay undecorated? Setting
  `triggerOnPrReady=off` per BT now does exactly that — silent on the
  automatic path, while an explicit Run still runs and reports.
- Should experimental branches be excluded from TC's *cleanup* rules
  differently (they can pile up)?

### F2 — Draft PR opened (work in progress)

**Trigger:** `pull_request.opened` with `draft=true`.
**TeamCity:** A1 (fast checks) is enqueued; A2/A3 are suppressed.
**GitHub:** A1 transitions Queued → In progress → success/failure. A2
shows **"Skipped: draft PR"** (`conclusion=skipped`).
**Config:** A1 `triggerOnPrDraft=on`; A2 `triggerOnPrDraft=off`.
**Note:** GitHub treats a `skipped` conclusion as satisfying a required
check, so a draft PR whose A2 is skipped is not blocked *by the check*
— it's blocked by being a draft. Worth verifying on your GitHub
Enterprise version before relying on it.

### F3 — Draft → ready for review

**Trigger:** `pull_request.ready_for_review`.
**TeamCity:** A2 is enqueued now. A1 is *smart-skipped* if a build
already exists for `(pull/N, head SHA)` — no duplicate.
**GitHub:** the A2 row flips from "Skipped: draft PR" to Queued → In
progress → result, at the same head SHA.
**Config:** nothing extra.

### F4 — New commit on a ready PR

**Trigger:** `pull_request.synchronize`.
**TeamCity:** A1 + A2 re-enqueued at the new SHA. Builds already
running on the *previous* SHA are **not** cancelled (deliberate: the
plugin never stops a running build).
**GitHub:** new Check Run rows at the new SHA; the old SHA's rows stay
as history.
**Your call:** on a busy PR this burns agents on obsolete SHAs. Do
you want a "cancel superseded PR builds" behaviour? That's an
enhancement (the plugin only removes *queued* builds today) — and
TeamCity's own queue optimiser already handles part of it.

### F5 — Expensive suite kept out of the default path

How many tasks a pull request runs is a TeamCity configuration decision
— the bridge does not manage queue load. What it
does provide is a way to express **scope** — "this suite is not part of the
default check set, ask for it" — through three combinable mechanisms for A3:

| Mechanism | Config | How a dev asks for it |
|---|---|---|
| Label | `labelFilter=+:ci-full` | add the `ci-full` label |
| Approval | `runOnApproval=true` | a reviewer approves the PR |
| Comment | `commentTrigger=/full` | inline review comment `/full` on the diff |
| Title/body phrase | `requirePhrase=/full` | write `/full` in the PR description |

**Important, verified in the code:** `runOnApproval=true` and
`commentTrigger` are *additional* trigger paths — they do **not** remove
the build from the normal `opened`/`synchronize` path. A BT with only
`runOnApproval=true` still runs on every PR push. To make A3 genuinely
on-demand you must *also* filter it out of the automatic path
(`labelFilter`, `requirePhrase`, or a PR branch filter). That combination
works: the comment/approval enqueue is stamped as an explicit command, so the
queue cleaner leaves it alone.

**A label is a trigger.** `pull_request.labeled`,
`unlabeled` and `edited` are handled: adding `ci-full` (or editing the title
to drop a skip phrase) re-evaluates the commit and enqueues what just became
eligible. Those actions deliberately post **no** "Skipped" row — the commit
has not changed, so a skip row would overwrite the result an earlier build
already published for it.

**Note on comment triggers:** they fire on
`pull_request_review_comment` (inline comments on the diff), not on the
PR conversation thread, and only for authors whose
`author_association` is in `comment.allowedAssociations` (default
`OWNER,MEMBER,COLLABORATOR`).

### F6 — Monorepo: a PR that touches only docs

**Trigger:** any PR event.
**TeamCity:** BTs whose `pathFilter` doesn't match any changed file are
dropped before enqueue.
**GitHub:** **"Skipped: paths out of scope"**.
**Config:** e.g. A2 `pathFilter=+:src/**`, doc-only BT `+:doc/**`.
**Caution:** path filtering **fails open** (token failure or empty file
list ⇒ everything runs) — intentional, but it means a path filter expresses
*scope*, never a guarantee: not a security boundary, and not a cost control
you can rely on.

### F7 — PR that targets a `Release/*` branch instead of the default branch

**Trigger:** `pull_request.opened`, `base=Release/26.06`.
**TeamCity:** identical to F2/F3 — the plugin builds the PR ref whatever the
base. (Per R16 that ref resolves to the PR **head**, not GitHub's
merge preview; check your VCS root's branch spec if in doubt.)
**GitHub:** same Check Runs.
**Known limitation:** there is **no base-branch (target-branch)
filter**. `prTrigger.branches` matches the PR's *source* branch
(`headRef`), not its target. So "run the release-hardening suite only
for PRs targeting `Release/*`" is not expressible in the feature config
today.
**Workaround available now:** the build reads
`teamcity.github.bridge.pullRequest.targetBranch` and self-skips inside
the build script (or via a TC parameter condition).
**One single set of required checks (R14)** for this project — a
PR targeting `Release/26.06` is gated exactly like a PR targeting
`<default branch>`. A target-branch filter would be needed only by a
deployment that gates release PRs differently; the bridge has none.
**Your call:** should a fix landing on `Release/26.06` also be
validated against `<default branch>` *before* merge (forward-port check),
or is the weekly cascade (F13) the only validation of that direction? With
a human cascade (R13) the answer is probably "the cascade is enough,
because a human is watching it".

### F8 — A PR build goes red

**Trigger:** A2 fails.
**GitHub:** `conclusion=failure`; the Check Run's `output.text` carries the
build's failure reasons, `details_url` deep-links to the TeamCity build page,
and the compiler diagnostics are **annotated on the lines of the diff** that
produced them (`checkRun.annotations`).
**Recovery paths:**
1. Push a fix ⇒ F4.
2. Click **Re-run** on the Check Run ⇒ `check_run.rerequested`, the
   plugin enqueues a fresh build at the same SHA even though a finished
   one exists (flaky-test escape hatch).
3. Comment the trigger phrase ⇒ same effect if `commentTrigger` is set.
**Your call:** re-run is currently open to anyone who can see the
Checks tab (GitHub gates it on write access). Fine? Or should re-runs be
restricted like comment triggers?

### F9 — PR is approved, then merged

**Trigger:** `pull_request.closed` with `merged=true`.
**TeamCity:**
1. Builds still **queued** on `pull/N` are removed from the queue
   (comment: `teamcity-github-bridge: PR #N merged`). Running builds
   finish.
2. The merge commit on the protected base triggers **A4/A5** through
   TeamCity's own VCS trigger — the plugin does **not** listen to
   `push`.
**GitHub:** post-merge Check Runs are posted at the merge commit SHA on
the protected branch (visible on the commit, not on the closed PR).
**Config:** A4/A5 need `triggerOnBranch=on` **and** a VCS trigger (or a
scheduled trigger) — the bridge gates and reports branch builds but
never *starts* them.
**Who owns a red post-merge build:** **TeamCity does the routing.** Its
*investigation auto-assignment* already attributes a broken build on a
protected branch to whoever most likely caused it, which is exactly what is
needed here — so the bridge stays out of it. It was briefly specified as a
bridge feature (name the merged PR and its author on the failing build) and
that idea is **dropped**: it would duplicate a mechanism TeamCity does better,
with an extra GitHub call per failing branch build.

What the bridge still contributes on that path is the *signal*: the Check Run
on the merge commit, with the failure reasons in its body, so the breakage is
visible from GitHub as well as from TeamCity.

### F10 — Direct push to a protected branch

**Trigger:** someone tries `git push origin master`.
**GitHub:** rejected by branch protection; no webhook, no TeamCity
involvement.
**Note:** if an admin bypasses protection, the push *does* land and A4
runs (F9 step 2) — nothing in the plugin distinguishes an
admin-bypass commit from a merge commit. Acceptable, or worth a check?

---

## 3. Release branches and the weekly cascade

### F11 — A new `Release/26.09` branch is cut

**Trigger:** ops branches from the default branch.
**TeamCity:** if `branchTrigger.branches` uses `+:Release/*`, the new
branch is picked up with **zero configuration**; the VCS root's branch
spec must also include it.
**GitHub:** first Check Runs appear on the branch as soon as A5 runs.
**Checklist to formalise:**
- VCS root branch spec covers `Release/*`.
- `branchTrigger.branches` covers `Release/*` (project level, so all
  BTs inherit).
- Which BTs must be *pinned* to a specific release (e.g. A7 packaging
  with a version number)?
- Retention/cleanup policy for the previous release's builds.

### F12 — Hotfix on an older release branch

**Trigger:** `Bugfix/z` → PR → `Release/26.03`.
**Flow:** F2/F3/F7 for validation, F9 on merge, then A5 on
`Release/26.03`, then A7 packaging of the patch, then the cascade
(F13) carries it to `Release/26.06` and to the default branch.
**Forward-port validation (same answer as F7):** the weekly cascade is the
validation of that direction, and R13 makes it human-driven, so somebody is
watching. The residual risk window — the fix lives in `Release/26.03` but is
unvalidated against `<default branch>` until the cascade runs — is accepted.
**Your call:** should the packaging pipeline (A7) run automatically after
every hotfix merge, or only on demand?

### F13 — Weekly cascade merge

**Trigger:** a **human** (R13) opens `Release/26.03 → Release/26.06`, then
`Release/26.06 → <default branch>`. Because both ends are protected, each
hop is a PR.

```mermaid
flowchart LR
    R1["Release/26.03<br/>modified"] -->|"cascade PR, human-opened"| R2["Release/26.06"]
    R2 -->|"cascade PR, human-opened"| M["master<br/>(default branch)"]
    subgraph TC["TeamCity, per cascade PR"]
      A1["A1 fast checks"]
      A2["A2 full suite"]
      A10["A10 cascade-specific checks"]
    end
    R1 -.-> TC
    R2 -.-> TC
```

**TeamCity:** each cascade PR is an ordinary `pull/N` — A1/A2/A10 run,
Check Runs are posted, required checks gate the merge exactly like any
other PR. With R14 (one single required set) there is nothing special to
configure at all.

**Why R13 (human cascade) simplifies this a lot:**

- the PR author is a team member, so **comment triggers, approvals and
  labels behave normally** — no bot-author problem, no need to
  drive anything through the external API;
- somebody is watching the cascade, so a **conflict (F14) is handled on the
  spot** rather than silently blocking the weekly propagation;
- an approval-gated suite (A3, `runOnApproval`) is actually reachable on a
  cascade PR, because a human reviews it.

**Still worth knowing:**
- **Chaining:** hop 2 can only start once hop 1 is merged, so the cascade
  is inherently serial and its total latency ≈ the sum of the slowest
  required check per hop. R14 says we accept that (same required set
  everywhere); if it ever becomes painful, a `cascade` label + `labelFilter`
  is how you would trim the set without inventing per-target rules.
- A3 without any filter runs on the cascade PR's `opened` event like on any
  PR (F5) — check that this is intended for the expensive suites.

**Your call:** should the cascade be *blocked*, or just reported, when
the source release branch's own post-merge build (A5) is already red before
the cascade starts? Merging a known-red branch upward propagates the
breakage — but with a human driving, this may simply be a judgement call
rather than a rule to encode.

### F14 — Cascade merge with conflicts (frequent, by R13)

**Trigger:** the cascade PR cannot merge cleanly — this happens **often**,
which is precisely why the cascade is human-driven.
**TeamCity:** nothing dramatic — **because we build the head, not the merge
preview** (R16). The PR's head branch exists and is perfectly buildable, so
the checks run and report normally; it is only the *merge* that GitHub
refuses. (A deployment that did build `refs/pull/*/merge` would be in the
opposite situation: GitHub cannot produce that ref while the PR conflicts, so
the builds would be absent or stuck on the last good merge commit.)
**GitHub:** the PR shows the conflict and blocks merging; the checks
themselves are green/red on their own merit.
**Resolution path:** the human resolves the conflict — typically by merging
the target into their local copy and pushing the resolution to the cascade
PR's head branch. Each such push is a plain `synchronize` (F4), so the
checks re-run on the resolved tree and the PR is validated normally. No
special support is needed from the plugin; the only thing to be careful
about is **not reading a stale Check Run** from before the resolution
(GitHub keys rows on the SHA, so this is handled automatically).
**Your call:** do you want an alert when a cascade PR sits unmerged for
more than N days (conflict abandoned mid-way)? Nothing in the plugin does
this; the metrics endpoint plus an external watcher is the practical route.

### F15 — A release branch is retired

**Trigger:** `Release/26.03` reaches end of support.
**Checklist:** remove it from the VCS branch spec (or narrow
`branchTrigger.branches`), stop A6 nightlies for it, decide whether the
cascade skips it, archive/pin its last packaging build.
**Note:** nothing breaks if you forget — you just keep paying for
builds on a dead branch. A periodic review of live `Release/*` branches
vs the branch spec is worth a calendar entry.

---

## 4. QA and manual test phases

**Read R15 first — it reframes this whole section.** QA is a **reviewer**,
not a gate: no formal GitHub status today, findings tracked in an external
bug tracker, and no QA-specific tooling to integrate (so "post an arbitrary
Check Run from outside" was dropped from the backlog). What QA actually
asks for is a **reference to a set of builds** and their artefacts, which
they can reach from GitHub — they do have GitHub access.

So the useful work was **F26** (make the builds and artefacts discoverable
from the PR) and **F16** (let them start a deployment). The blocking-check
pattern of F17 is documented but **not what
this project wants** — keep it in mind only if the stance ever changes.
Archetype A9 is therefore *not* a required check (see §1).

### F16 — Deploying a release candidate to a QA environment

**Trigger:** on demand (QA lead) or after A7 packaging succeeds.
**Ways to start it today:**
1. **Manual run in TeamCity** — always allowed; a manual trigger
   bypasses the soft filters (branch list, path, metadata).
2. **Inline PR comment** `/deploy-qa` — `commentTrigger` on A8, works
   only in a PR context and only for trusted associations.
3. **External API** —
   `POST /api/trigger {"buildTypeId":"...","branch":"Release/26.06"}`
   with the bridge's bearer token. This is the only path that works on
   a plain branch (no PR) from outside TeamCity.
**GitHub:** A8's Check Run is posted at the built SHA — on a
`Release/*` commit it shows on the commit page; there is no PR to
decorate.
The deployment is a **TeamCity build** (R15), there is no
external QA tool to integrate. So path 1 (manual run) and path 2 (comment
trigger) are the realistic ones, and the plugin needs no new endpoint for
this.
**Caveat on path 2:** a comment trigger only fires for authors whose
`author_association` is trusted — i.e. people with **write** access. If QA
accounts are read-only on the repository, they can *see* everything but
cannot start anything from GitHub; the deployment then has to be started by
a developer, or by a schedule, or from TeamCity directly.
**Your call:** one QA environment per release branch, or a shared one?
(Affects whether A8 can run concurrently for two branches.)

### F17 — Recording a manual-test verdict as a GitHub check

Not used in this deployment — QA is a reviewer, not a gate (rule 15) — but
this is the pattern if a verdict ever has to *block* a merge.

Make the human verdict **a build** (archetype A9), so the bridge turns it
into a Check Run that branch protection can require:

```mermaid
sequenceDiagram
    actor QA
    participant GH as GitHub PR
    participant BR as GitHub Bridge
    participant TC as TeamCity (sign-off build)

    QA->>GH: runs the test plan, then<br/>inline review comment "/qa-ok"
    GH->>BR: pull_request_review_comment (author trusted)
    BR->>BR: phrase matches the build's commentTrigger?
    BR->>TC: enqueue the sign-off build at the PR head
    TC->>BR: buildFinished (success)
    BR->>GH: Check Run "TeamCity / QA_Signoff" = success
    Note over GH: required check satisfied → merge unblocked
```

**Why it holds up:** a Check Run is keyed on `(name, commit)`, so a new
commit **automatically invalidates the sign-off** — the new commit has no
`QA_Signoff` row and the required check blocks the merge again. That is
exactly the semantics manual QA needs.

**How to keep it off the automatic path.** The build must not report success
before anyone has tested, so it needs a filter that excludes it from
automatic triggering while leaving the comment path open — a `requirePhrase`
that no PR carries, or a label the tester sets. An explicit command bypasses
the filters and is never removed from the queue, which is what makes the
combination work:

```
QA sign-off build feature:
  triggerOnPrReady = on
  triggerOnPrDraft = off
  requirePhrase    = <a phrase no PR body carries>
  commentTrigger   = /qa-ok
```

Two things to decide if you adopt it: whether a rejection posts a red
`QA_Signoff` (or simply leaves the row absent, which already blocks), and
whether the tester's identity must appear in GitHub — the comment is already
the audit trail, and the bridge names the commenter in the build's trigger
comment.

### F18 — QA finds a bug

**Trigger:** QA finds a defect while reviewing.
**What happens (R15):** the finding goes to the **external bug tracker**,
not to GitHub. If it results in a fix, that fix is an ordinary push to the
PR branch ⇒ F4: new SHA, all Check Runs re-run, previous results become
history.
**Consequence for us:** nothing to build. The one thing worth having is the
**reverse link** — from the build/artefact QA tested to the exact commit and
PR — so a tracker ticket can name a precise build. The PR parameters already
published by the bridge (`pullRequest.number`, `headSha`, …) are what a build
should stamp into its artefacts or build number for that purpose.
**Your call:** should the tracker ticket reference the **TeamCity build
id** or the **commit SHA**? The SHA survives build cleanup; the build id
points at the artefacts. Probably both.

### F19 — QA campaign on a release branch (no PR)

**Trigger:** end-of-sprint validation of `Release/26.06` as a whole.
**Problem:** there is no PR, so no Checks tab to gather anything, and
required checks do not apply. With R15 this is fine — nothing needs to be
*gated*. What QA needs is a **reference to the set of builds** for that
branch (R15/answer 11).
**What that means concretely:**
- The Check Runs of A5/A6/A7 are posted on the branch's head **commit**, so
  they are reachable from the commit page on GitHub — discoverable, but only
  if you know which commit to look at.
- The natural "one link for QA" object is therefore either a **GitHub
  Release / tag** on the validated commit (which then shows its checks), or
  the **Branches & PRs** tab filtered on that branch (F25).
**Your call:** what is the artefact of record for "release X was
QA-validated" — a TeamCity pin, a tracker ticket, a GitHub release? The
bridge should *decorate* that object, not invent a competing one.

---

## 5. Operations and edge cases

### F20 — On long-lived branches: some builds on push, others scheduled (R11)

**Requirement:** on the default branch and each live `Release/*`, part of
the pipelines must run **at every push** (A4/A5) and part on a
**schedule** — nightly, weekly, whatever (A6). Both on the same branch.

**Who owns what:** TeamCity owns the *when* (VCS trigger vs schedule
trigger, per build configuration); the bridge owns the *whether* and the
*reporting*. There is no scheduling in the plugin, and none is needed —
the bridge gate treats a scheduled build exactly like a VCS-triggered one
(`isManual=false`, branch list applies).

| Cadence | TC trigger on the BT | Bridge config | Notes |
|---|---|---|---|
| every push | VCS trigger, branch filter `+:<default>` / `+:Release/*` | `triggerOnBranch=on`, branch list matching | the post-merge alarm of F9 |
| nightly | schedule trigger, "only if there are pending changes" **off** if you want a build even on an unchanged branch | same | catches environment rot, not just code changes |
| weekly | schedule trigger | same | good place for the very expensive matrix |

**⚠️ Check Run name collisions.** A Check Run is keyed on
`(name, head SHA)`. Two different build configurations that build the same
commit therefore **must not share a name**, or the second overwrites the
first — a nightly failure would erase the green post-merge row on the same
commit. Since the name is `TeamCity / <BT full name>`, distinct build
configurations are automatically distinct; the risk only appears if you
reuse one BT for both cadences (e.g. two triggers on the same BT). Rule of
thumb: **one cadence per build configuration**.

**Your call:** for the scheduled suites, do you want a GitHub Check
Run at all? They report on a commit that is often already merged and
green; some teams prefer them to stay TeamCity-only (drop the bridge
feature from those BTs) and to alert through TC notifications instead.

### F21 — TeamCity is down during a merge window

**Trigger:** maintenance while PRs are being merged.
**Behaviour:** GitHub retries webhook deliveries for up to 24 h, so PR
events land after restart. But **post-merge branch builds depend on
TeamCity's VCS polling**, which simply resumes and picks up the new
commits. Queued builds are restored from disk.
**Risk:** required checks on open PRs stay pending during the outage —
merges are blocked, which is the safe direction.

### F22 — Rolling this out safely

Use **dry-run** first: the plugin logs `[dry-run] would enqueue …` /
`would POST … Check Run` without doing anything. Watch a full week —
including one cascade window (F13) — then turn it off. Enable the repo
allowlist while piloting on a single repository.

---

## 6. Platform rules of this deployment

The constraints that shape everything above, and the two features that came
out of them: forks (R9), the single status publisher (R10), the branch/PR view
(R12), the QA hand-off, re-running from GitHub, and branch-source builds
(R16/R17).

### F23 — Forks are ignored (R9)

**The rule, restated:** the bridge is attached to **one repository**, never
to its forks. A PR whose head lives elsewhere is not "unsupported", it is
**out of scope and must be ignored**. This one is a *plugin default*, not
just this team's policy.

**What it buys us.** Every PR head is a branch of the same repository, so:

- the head ref is always one of `Feature/*` / `Bugfix/*` / `Experiment/*` /
  `Release/*` — the PR branch filter (`prTrigger.branches`, matched on
  `headRef`) is meaningful and exhaustive;
- the installation token always covers the head repo, so PR-file listing
  (path filters, F6) and PR metadata reads never hit a permission wall;
- `author_association` is never that of an outsider, which makes the
  comment-trigger allowlist (`OWNER,MEMBER,COLLABORATOR`) effectively
  "anyone who can push" rather than a security boundary;
- there is no "untrusted code builds with our credentials" problem — the
  classic reason CI systems refuse to run fork PRs automatically.

**How it works.** The head repository is parsed from the
`pull_request` / `pull_request_review` payloads and from the REST answer
(`PrInfo.headRepo`, which covers the comment path); an event whose head lives
elsewhere is logged, counted (`fork_events_ignored`) and dropped. A blank head
repo — GitHub omits it for a deleted fork — fails open rather than being
treated as foreign. This was the prerequisite for F28: a fork's head ref does
not exist locally and could not be built at all.

**If forks are ever allowed later:** revisit this list first — it is the
shortest path to a security review — and keep those projects on the `pull/N`
model (the per-project switch of F28 exists exactly for that).

### F24 — The bundled `commitStatusPublisher` is banned when the bridge is on (R10)

**Why:** the two produce parallel, competing signals on GitHub — TC's
Commit Status ("TeamCity build finished", generic) and the bridge's Check
Run (rich text, `details_url`, skip reasons). Branch protection can end up
requiring the wrong one, and reviewers see two rows per build.

**Decided (question 9): the plugin warns, and never acts.** Auto-disabling
the bundled feature is rejected — choosing what reports to GitHub is a
configuration decision that belongs to the operator, refusing to publish
would be worse than a duplicate row, and the mechanics have no public API
anyway (roadmap Item 4 now records this decision). What the bridge owes the
operator is a warning they cannot miss; acting on it is their call.

Two things follow:

- **Turn on `legacyAliases.enabled`.** With the bundled feature off,
  publishing the legacy `teamcity.pullRequest.*` parameter names is
  collision-free, and existing build scripts keep working. That flag exists
  precisely for this transition.
- **The conflict is surfaced.** A build configuration carrying
  both features gets a `WARN` line at server startup and a **Single status
  publisher** row in the admin page's self-tests, listing the offenders. Both
  read the *resolved* feature set, so a publisher inherited from a template is
  caught. No behaviour change: the plugin tells, it does not act.
- **Document it for users, not just here.** The requirement now appears in
  the user-facing docs — [quickstart.md](quickstart.md) step 4 (removing the
  bundled feature is part of opting a build configuration in),
  [configuration.md](configuration.md#choosing-the-right-setup) (recommended
  setup + why the plugin will not do it for you) and
  [troubleshooting.md](troubleshooting.md#symptom-pr-shows-two-teamcity-entries-commit-status--check-run)
  (including the template-inherited case, which is easy to miss since the
  bridge reads the *resolved* feature set).

### F25 — Branch ↔ PR association, and one view searchable by either

**What you get:** one list of the bridge's builds — the **Branches & PRs**
project tab — where every row carries both keys, the branch it ran on *and*
the pull request it belongs to. Type a branch name or a PR number and you
get the same list, filtered; sort by time, branch or PR.

**Why it can be that simple:** GitHub allows **at most one open pull request
per head branch**, so `Feature/x ↔ PR #189` is a 1:1 mapping and the only
question is whether the PR exists. No disambiguation logic.

```mermaid
flowchart TD
    A["A build exists in TeamCity"] --> B{"Branch name?"}
    B -->|"pull/N"| C["PR number = N<br/>from the ref, no API call"]
    B -->|"master / Release/*"| D["Long-life: no PR expected"]
    B -->|"Feature/* or Bugfix/*"| E{"Attached to an open PR?"}
    E -->|no| F["Build stays PR-unaware"]
    E -->|yes| G["GET /commits/{sha}/pulls<br/>open PR whose head is this commit"]
    G --> H["PR resolved: parameters, draft/ready tag,<br/>PR tag, summary comment, Check Run on the PR"]
    C --> I["Searchable by branch AND by PR"]
    H --> I
```

**How the PR column is filled** — and why the page costs no GitHub call:

| Source | When it applies |
|---|---|
| The `pull/N` ref | Trivially, the ref carries the number |
| The build's **PR tag** (`pr-189` by default) | Written when the build runs, and back-filled on `pull_request.opened` / `synchronize` for builds that ran *before* the PR existed |
| `GET /commits/{sha}/pulls` | Once per built commit, cached (negative answers included), to resolve the PR of a plain branch build |

The tag is optional (`prTag.enabled`) and its prefix configurable
(`prTag.prefix`). With tagging off, the PR column shows only what the ref
says: a `pull/N` build keeps its number, a build on a work branch loses it.

Branch-source mode (F28) simplifies the whole picture: one branch namespace,
the PR number as an attribute, and the back-fill becomes mostly unnecessary
because the build was never on a different ref.

### F26 — QA reaches the builds from the pull request

**The old way:** QA is handed a *branch name* and launches, by hand in
TeamCity, the builds normally reserved for long-life branches — installer,
deployment. It works, but QA needs TeamCity knowledge, the choice of build is
implicit, and the artefacts are discoverable only inside TeamCity.

**What the pull request carries instead.** The sticky summary comment is the
hand-off document: one row per check, its result, a link to the build, and a
**direct download link per artifact** — the installer itself, not a TeamCity
page. A tester who can read the PR needs nothing else.

For a release-branch campaign there is no pull request to carry the table;
the **Branches & PRs** tab filtered on that branch is the shareable
equivalent (F19).

**Starting a deployment from the PR.** Put a `commentTrigger` on the
deployment build (archetype A8) and a tester can start it from the PR thread
with no TeamCity access at all. One caveat: a comment trigger only fires for
authors GitHub reports as trusted — people with **write** access. Read-only
QA accounts can see everything and start nothing, so the deployment then has
to be started by a developer, by a schedule, or from TeamCity.

**The change of stance this implies:** the QA-visible pipelines (installer,
deployment) become ordinary opted-in build configurations that report on pull
requests, instead of being reserved for long-life branches. Everything else
is presentation.

### F27 — Re-running checks from GitHub

Every re-run affordance in the GitHub Checks UI works, including the two
that are easy to get wrong:

| Click | GitHub event | What happens |
|---|---|---|
| **Re-run** on a check that passed or failed | `check_run.rerequested` | the Check Run name is mapped back to its build configuration and a fresh build is enqueued, even though a finished one exists at that commit |
| **Re-run** on a check that reads "Skipped: …" | `check_run.rerequested` | the build **runs**: an explicit request bypasses the branch, path and metadata filters that skipped it in the first place, and nothing removes it from the queue afterwards |
| **Re-run all checks** | `check_suite.rerequested` | every opted-in build configuration for that head is re-run |

Re-running a *skipped* row is the useful one: it turns every "Skipped: …"
into a one-click escape hatch when a path filter or a label rule was too
aggressive.

Two things still hold a re-run back — deliberately. A **project-level kill
switch** (`prTrigger.enabled=false`) mutes the bridge for that path
entirely, and a build configuration with **publication off** runs but says
nothing on GitHub.

With **`rerunAll.onlyFailed`** on, "Re-run all checks" is restricted to the
configurations whose last build at that commit failed.

> The managed App must subscribe to `check_suite`. Apps created before
> 1.9.0 do not: **Verify App configuration** on the admin page reports it as
> a missing event until you add it, and the button stays silent meanwhile.

### F28 — Branch-source builds instead of `pull/N`

By default a PR build runs on the synthetic **`pull/N`** ref: TeamCity's
Branch column shows `pull/189`, and a work branch that also has a VCS trigger
builds **twice** per push — once as the branch, once as `pull/N` — with both
builds fighting over the same Check Run row, which GitHub keys on
`(name, commit)`.

Tick **Build PRs on their own branch** on the project
(`teamcity.github.bridge.prBuildRef = branch`) and the bridge enqueues PR
builds on the PR's **head ref** instead:

```mermaid
flowchart LR
    subgraph P["prBuildRef = pull (default)"]
      P1["pull_request event"] --> P2["build on pull/189"]
      P3["push on Feature/toto"] --> P4["second build, same commit"]
      P2 --> P5["Check Run at the head commit"]
      P4 --> P5
    end
    subgraph B["prBuildRef = branch"]
      Q1["pull_request event"] --> Q2["build on Feature/toto"]
      Q3["push on Feature/toto"] --> Q2
      Q2 --> Q4["one build per commit<br/>readable branch name"]
    end
```

**What it buys**

- **One build per commit.** There is no second ref, so a pre-PR build and the
  PR build are the same build — and "build on every push" costs nothing extra.
- **Readable branch names** in every TeamCity screen, which is also what makes
  the Branches & PRs tab (F25) worth reading.
- **The PR gates still apply.** "Is this a PR build?" is answered from the
  built commit, not from the ref name, so `triggerOnPrDraft`, the PR branch
  filter and the metadata filters keep working.

**What it requires**

| Requirement | Why |
|---|---|
| The head branches are in the VCS root's branch spec (e.g. `+:refs/heads/Feature/*`) | TeamCity can only build a ref it knows about — and a branch absent from the spec cannot be picked in the Run dialog either |
| Pull requests come from branches of the same repository | A fork's head ref does not exist locally. The bridge ignores fork PRs anyway (F23), so this is a statement of scope |
| **One automatic starter per build configuration** | A push to the head branch is the same event whether a PR exists or not. Give a configuration both a VCS trigger *and* the bridge's PR path and the two race for that push: the same commit builds twice, because the bridge's dedup only sees what is already queued and cannot know a VCS trigger is about to fire. Pick one — the VCS trigger (with the bridge's PR triggering off) for pre-PR feedback, or the bridge alone |

The setting is **per project** and defaults to `pull`, so nothing changes
until you tick the box. Builds that already ran on `pull/N` stay in the
history as builds of a ref that stops receiving new ones. Full reference in
[configuration.md](configuration.md#branch-source-pr-builds-v190).

## 7. Summary: GitHub event → TeamCity → GitHub

| Where the code is | GitHub event | Who starts the build | Archetypes | GitHub feedback |
|---|---|---|---|---|
| `Feature/*` / `Bugfix/*`, no PR (R7) | *(push only)* | a human clicking Run, `POST /api/trigger`, or a VCS trigger if the automatic policy is chosen | A12 | Check Run on the commit; attached to the PR as soon as one exists (`branchPrLookup`) |
| experimental branch, no PR | *(push only)* | **a human clicking Run** | A11, or any BT | Check Run on the commit (nothing until someone runs it) |
| experimental branch with a PR | any PR event | nobody automatically; a human clicking Run | A11 | "Skipped: branch out of scope" automatically; a real Check Run once run by hand |
| draft PR | `pull_request.opened(draft)` | bridge listener | A1 | A1 result + A2 "Skipped: draft PR" |
| PR ready | `ready_for_review` | bridge listener | A1, A2 | full check set |
| PR updated | `synchronize` | bridge listener | A1, A2 | new rows at new SHA |
| PR, opt-in suite | comment / approval / label+event | bridge listener | A3 | extra rows |
| PR, out-of-scope paths/metadata | any PR event | — | — | "Skipped: paths / PR metadata out of scope" |
| PR merged | `closed(merged=true)` | bridge drains queue; **VCS trigger** builds the base | A4 / A5 | Check Runs on the merge commit |
| protected branch, on push | *(push, not handled by the plugin)* | TC **VCS** trigger | A4, A5, A7 | Check Runs on the commit |
| protected branch, scheduled (R11) | *(none — time-based)* | TC **schedule** trigger | A6 | Check Runs on the branch head; distinct BT ⇒ distinct name, no collision |
| cascade PR | `pull_request.opened` (bot) | bridge listener | A1, A2, A10 | same as a human PR |
| QA deploy | comment / API / manual | bridge or API | A8 | Check Run on the built SHA |
| QA verdict | inline comment `/qa-ok` | bridge listener | A9 | `QA_Signoff` Check Run — **reference pattern only**, not used here (R15) |
| red **or green** check, re-run | `check_run.rerequested` | bridge listener | the same BT | fresh row, same SHA (works today, F27) |
| **skipped** check, re-run | `check_run.rerequested` | bridge listener | the same BT | the build runs: an explicit request bypasses the filters that skipped it |
| **re-run all** | `check_suite.rerequested` | bridge listener | every opted-in BT at that head | whole check set re-runs (optionally only the failed ones) |
| PR from a **fork** | any PR event | nobody — event dropped | — | nothing runs, nothing is reported |

> Every `pull/N` in this table is "the ref carrying the PR build". In
> branch-source mode (F28) that ref is the head branch itself, and the first
> row merges into the PR rows — same build, same Check Run, readable name.

---

## See also

- [usage-scenarios.md](usage-scenarios.md) — per-webhook mechanics
  (scenarios 1–29).
- [configuration.md](configuration.md) — every setting and where it
  lives.
- [roadmap.md](roadmap.md) — where the G-items above would land.
