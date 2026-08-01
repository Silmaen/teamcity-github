# Changelog

All notable changes to this project are documented in this file.
The format follows [Keep a Changelog](https://keepachangelog.com/)
and the project adheres to [Semantic Versioning](https://semver.org/).

## [1.10.0] - 2026-08-01

What GitHub sees of a build — why it failed, what its tests did, where its time
went — plus the pull request's own context inside TeamCity, and an agent freed as
soon as its verdict stops mattering.

### Added

- **A running build whose result has nowhere to go is stopped**
  (`cancelObsolete.enabled`, default on), in the two cases TeamCity handles by
  itself in neither:

  - **a new commit is pushed** to the pull request — the builds on the previous
    head kept an agent busy to produce a verdict about a commit nobody would look
    at again, and left an `in_progress` Check Run on it, while the build for the
    new head queued behind them. TeamCity drops obsolete *queued* builds by
    itself; started ones it keeps;
  - **the pull request is closed or merged** — the builds still *queued* were
    already removed, but a running one outlived the PR by however long it took to
    finish.

  `buildInterrupted` publishes the cancellation, so the commit gets an honest
  "Build cancelled" row rather than an `in_progress` one that never resolves.

  Never stopped, either way: a **personal** build and one somebody **started by
  hand** — the bridge only ever takes away a build it could have started itself.
  On a push, two more are spared: one whose **revision TeamCity has not resolved**
  yet, and the **last build in flight** for that branch, so an out-of-order
  delivery cannot leave the pull request with nothing running. Neither applies to
  a closed PR, where an empty ref is the goal.

  Subordinate to the `queueCleanup.enabled` master switch, and counted separately
  as `builds_stopped` — that one is agent time given back.

- **An infrastructure failure is named.** A lost checkout, an unresolvable
  artifact dependency or a runner that could not start used to turn a pull request
  red exactly like a failing test, and the reviewer could not tell "your code is
  broken" from "our CI broke" — the difference between fixing a commit and
  re-running a build. Build problems are now classified from their type
  (TeamCity's own internal-error set); an infrastructural failure says so in the
  title — *"Infrastructure failure: Unable to collect changes"* — and opens its
  summary with a line stating the commit was **not** verified.

  It still concludes `failure`, and the merge stays blocked. Naming a suspected
  cause costs nothing if the classification is wrong; *unblocking a merge* on a
  wrong guess lets an unverified commit through. So that half is opt-in:
  `checkRun.infraNeutral` (**default off**) concludes `neutral` instead, which
  GitHub counts as satisfied for a required check.

  Two deliberate exceptions: one problem the build itself produced (a failing
  test, a compile error, a non-zero exit code) makes the whole failure the
  **code's**, however many infrastructure problems came with it; and a failed
  **snapshot dependency** is named — *"Build failed: Snapshot dependency
  failure"* — but stays red, because that dependency may well have failed on this
  pull request's own code. Builds that never started for a failed dependency get
  the same treatment.

- **Test outcome in the Check Run** (`checkRun.testStats`, default on): the
  counts in the title GitHub shows in the merge box — *"Build failed — 3 of 1046
  tests failed (2 new)"* — and the failing tests in the body, new failures first,
  each with its duration and its failure text folded into a `<details>` block.
  Muted tests are counted apart and never listed as failures. A build that runs no
  test says nothing.

- **Timings in the Check Run summary** (`checkRun.timings`, default on):

  ```
  - **Total** — 11m 13s
  - **Run** — 7m 12s on `agent-3`
  - **Wait** — 4m 1s (dependencies 3m, free agent 1m, other 1s)
  ```

  "Run" is working time, never waiting. The dependency share runs to the instant
  the last snapshot dependency finished, so it includes the wait of the dependency
  itself. Only what TeamCity itself blames on agent availability is counted as
  such — the dates say how long a build waited, never why — so wait that nothing
  explains is shown as "other". **`started_at` / `completed_at` are sent** too, so
  GitHub renders the duration itself instead of inferring it from when our request
  happened to arrive.

- The Check Run body follows a fixed order: **failure cause, tests, artifacts,
  link to the build in TeamCity**.

- **A "Pull request" tab on the build page.** The bridge carried everything one
  way; standing on a build page there was no way to open the pull request being
  judged. The tab states what the build is judging — number, title, author,
  `draft`/`ready`, labels, both branches, the head commit and the size of the
  change — links to the pull request and to its Checks, Files and Commits, and
  lists the changed files (`prTab.changedFiles`, default on, first 100). It is
  hidden, not empty, on a build with no pull request.

  Everything but the file list comes from parameters the build already carries, so
  it costs no API call. The file list comes from the PR-info cache, filled by the
  same compare call that resolves the merge base: free on a warm cache, one call
  on a cold one, and only because a human opened the tab. It is therefore the pull
  request **as it stands now**, not as the build saw it, and the page says so.

  (`BUILD_RESULTS_TAB` is the one place on a build page the current UI renders —
  `PlaceId.BUILD_SUMMARY` and `BUILD_ACTIONS` are classic-page only, and a
  client-side overlay would go stale on a single-page app, where a link to the
  wrong pull request is worse than no link.)

- **Eight more published parameters** — what the pull request *is* and what it
  *changes*, so a build step no longer has to work it out for itself:
  `…pullRequest.url`, `.baseSha`, `.mergeBase`, `.changedFiles`, `.additions`,
  `.deletions`, `.commits`, and `.labels` (comma-separated, in GitHub's order —
  the same list the metadata gate filters on). Always defined, empty on a non-PR
  branch, like the eight before them.

  **`mergeBase` is the one that matters**: `git diff <mergeBase>..<headSha>` is
  the pull request's own change, while diffing against the base branch's head also
  shows everything that landed on the base since — so a diff-scoped analysis given
  the latter reviews other people's commits. It costs one `compare` call **per
  cache fill**, not per build, and both it and the tab's file list are checkboxes
  on the admin page (`mergeBase.enabled`, `prTab.changedFiles`) for a server close
  to its GitHub rate limit.

- **A project can shorten its Check Run names**
  (`teamcity.github.bridge.checkName.stripPrefix`, on the project's *GitHub
  Bridge* tab). On a deep tree the name is mostly ancestry a reviewer does not
  need, while GitHub's merge box truncates the **end** — the part that identifies
  the build. **This renames the checks**: GitHub keys a row by
  `(name, head_sha)`, so a new row starts and any branch protection rule requiring
  the old name must be updated.

### Changed

- **Diff annotations can be switched off at three levels, and one "no" wins.**
  They are the only thing the bridge writes *on a reviewer's diff* rather than in
  a panel somebody chose to open, so each level gets a veto:

  - the **server** — `checkRun.annotations`, as before;
  - a **project** — `teamcity.github.bridge.annotations.enabled`, on its *GitHub
    Bridge* tab;
  - a **build configuration** — `annotateDiff`, on its *GitHub Bridge
    integration* feature.

  The verdict is the **AND** of all three, and the asymmetry is deliberate: a
  `true` never overrules a `false`, so unticking it on a project holds for its
  whole subtree and a sub-project cannot take it back — otherwise "no annotations
  from this tree" would not be enforceable anywhere. The project value is
  therefore read **own-per-project across the chain**, not resolved like every
  other project parameter, and the project tab names the ancestor that vetoes so a
  ticked checkbox never lies. Only the literal `false` decides; absent, blank,
  `true` or a typo all abstain.

  All three switches are on their respective **forms** — no hand-edited
  parameters — and `checkRun.annotationLogScan` joined the admin page next to
  them.

### Removed

- **The sticky PR summary comment** (`prComment.enabled`, `PrSummaryCommenter`,
  and the three issue-comment calls in `GitHubClient`).

  It was refreshed by **deleting the old comment and posting a new one** —
  `HttpURLConnection` refuses `PATCH`, so an in-place edit was not available — and
  GitHub notifies every watcher of a pull request on each new comment. A feature
  meant to keep one always-current summary therefore emailed everybody once per
  build. That is spam, whatever the content says, and fixing it properly meant
  replacing the HTTP layer for a feature the Checks panel already covers: the same
  per-configuration rows, the same status, the same artifact links.

  Two things follow. The **Artifacts** section of the Check Run is unaffected
  (`checkRun.artifactLinks` still governs it), and the App's **pull requests**
  permission comes back down to **read** — the comment was the only thing that
  ever needed write, so an installation that granted it can revoke it. Nothing
  else changes: the PR **comment trigger** is a different feature (it reads
  comments from the webhook payload, never through the API) and stays.

### Fixed

- **Personal builds publish nothing** — no `queued`, no `in_progress`, no
  conclusion, whatever `publishChecks` says: they verify a patch that is not in
  the repository, so no row may describe the commit. Triggering one by hand used
  to leave a Check Run stuck on **"Queued"** for good. They are also outside the
  queue dedup both ways (they never cover a commit, and the bridge never removes
  them), while still resolving their pull request: parameters, tags,
  retro-association.

- **Two lag bugs at `buildFinished`**, where TeamCity has not finished
  transitioning the build: `finishDate` is null (every build reported "Ran <1s",
  and no `completed_at`) and the status descriptor still reads "Running" (a green
  Check Run summarised "Running"). Both are handled.

- **Diff annotations now work for a Command Line build** — which is to say, for
  nearly every CMake/ninja C++ build there is. The feature read only the
  descriptions of the build problems TeamCity reports, on the assumption that a
  diagnostic worth pinning had reached one. It had not: a **Command Line** runner
  reports exactly one problem, *"Process exited with code 1 (Step: Build (Command
  Line))"*, and the compiler's own output never becomes a build problem at all. So
  the feature was on, correct, and silently inert for the builds that need it
  most.

  When the build problems carry no diagnostic, the failed build's **log** is now
  scanned instead (`checkRun.annotationLogScan`, default on), bounded on every
  axis: failed builds only, never when the problems already produced an annotation,
  and the log iterator stays lazy so the read stops at the 50th annotation or after
  200 000 lines — an error on line 900 does not cost a 2 GB read.

- **A "Clear cached tokens" button** on the admin page, next to *Verify App
  configuration*. An installation token carries the permissions it had **when it
  was minted** and is cached for 50 minutes, so granting the App a permission
  changed nothing for up to half an hour: every call kept failing with `403` for a
  permission the App visibly had. The button drops the tokens, and the PR-info
  cache with them, since a token with new scopes may see a pull request the old one
  could not.

- **Writing the PR tag and showing it are two settings now** (`prTag.enabled` and
  the new `prTag.display`, both default on). TeamCity's Tags column is narrow: a
  second tag on a build collapses it into a count and the `draft`/`ready` pill
  stops being readable, which made the PR tag a straight trade against the pill.

  Unticking *"… and show it in build lists"* now **keeps the tag and hides its
  chip**: TeamCity's own tag filter and search still find the build by PR number,
  the *Branches & PRs* column still shows it, and the pill gets its column back.
  Client-side, in the same injected fragment that colours the pills, and it only
  ever hides `<prefix><digits>` anchored at both ends — a team's own
  `pr-review-notes` is untouched.

- **A PR build is tagged with its PR number when it is queued**, not only when a
  later `pull_request` event happens to catch it at the pull request's current
  head. `PrPromotionTagger` had the number in hand — it resolves the PR to decide
  `draft`/`ready` — and wrote only the state tag, so every older build lost its PR
  number, and in **branch-source** mode there is no `pull/N` ref to recover it
  from.

- **The PR column of the *Branches & PRs* tab reads the build's own parameter**
  when neither the tag nor the ref knows the number — the number was in the
  build's published parameters all along. Three sources now, cheapest first: tag,
  ref, parameter. The practical effect is that the column fills in for **existing
  history** too, not only for builds queued after the tagging fix above.

- **`/info` reported TeamCity's version as its own.** The field was filled from
  `SBuildServer.fullServerVersion`, so `/info` answered
  `"pluginVersion":"2026.1.3 (build 222742)"` while `/health` answered the
  plugin's own — one key, two endpoints, two different meanings, and the API
  reference documented the wrong one as intended. `/info` now reports the plugin's
  version and adds a separate **`teamcityVersion`** field.

- **The title no longer claims an ignored test passed.** A build whose only test
  was skipped read *"Build failed — 1 tests passed"* in GitHub's merge box: the
  suffix reported the **total**, which counts the ignored and the muted tests too.
  It now reports what actually passed, names the rest, and counts one test in the
  singular — "no test passed, 1 ignored", "31 tests passed, 12 ignored".

- **A failing test whose failure text says nothing no longer gets a fold-out.**
  TeamCity's XML importers — CTest among them — report a bare `Failure` as both
  the status text and the short stacktrace, so every failing test carried a
  `<details>failure</details>` whose entire content was that one word. The real
  output is now read from `STestRun.getFullText()` when the cheap reads say
  nothing (the expensive read is paid for only then); a text that carries no
  information — `Failure`, `error`, the test's own name — is dropped rather than
  rendered; and what is left is rendered by length: a **one-liner goes on the
  bullet** (`` — `expected 3, got 4` ``, no click), anything longer is folded
  under a summary carrying **its first line** instead of the word "failure".

  And once the real output *did* reach GitHub, it turned out to be mostly the test
  framework talking about itself: a gtest run leads with `Failed`, a
  `------- Stdout: -------` banner and its `[==========]` bracket lines, and
  buries the assertion in the middle. The output is now **excerpted**: the
  scaffolding lines are dropped, the excerpt starts at the `file:line` anchor when
  there is one (`path:14:` and MSVC's `path(14):`), and it is capped at 12 lines.
  Conservative by construction: an unrecognised line is kept, and if the filters
  leave nothing the raw text is used unchanged.

  Two more things the same panel said badly: the **`(empty): ` prefix is gone from
  test names** (TeamCity substitutes that literal for a report with no suite; a
  suite that says something is still kept — it is what tells two identical test
  names apart), and **"N failed tests detected" is no longer listed under *Failure
  details*** when the *Tests* section is there.

- **The legacy aliases now use the spelling the bundled feature uses.**
  `legacyAliases.enabled` exists so DSL written against the bundled
  `pullRequests` feature keeps working, but it emitted the two branch names as
  `teamcity.pullRequest.sourceBranch` / `.targetBranch` — this plugin's own
  camelCase — while the bundled feature spells them
  `teamcity.pullRequest.source.branch` / `.target.branch`. The flag therefore
  missed its own purpose for exactly the two parameters a migration is most
  likely to read. Both spellings are emitted now, so nothing that already reads
  the camelCase pair breaks.

- **`draft` / `ready` pills are actually coloured**: `ready` green, `draft` a
  neutral grey, dark theme included. The colour now lands on the **chip** (Ring
  UI's `.ring-tag-container`) instead of the label inside it — painting the label
  put a coloured rectangle inside Ring's grey chip.

### Housekeeping

- Tests moved out of `src/` to a top-level `test/kotlin/`, same packages.
- Twelve leaf packages became seven: `cache` → `api`, `filter` → `queue`,
  `parameters` → `enrich`, `selftest` (with `PublisherConflictReporter`) →
  `config`, `retrigger` → `web`. Each held a single class. Base package
  unchanged — the Spring XML depends on every fully-qualified name.
- Dead code removed: four declarations with no caller; a sweep of 1169 found
  nothing else.
- `doc/roadmap.md` holds only future work now, with the next ideas written down
  (flaky tests, coverage trend, queue position, a Check Run left `in_progress`).
- **`doc/upgrading.md`**, new: what a release changes for the *operator* —
  a GitHub permission that can be revoked, an event to subscribe to, a default
  that changes what reviewers see, and how to roll back. Writing it is now a
  release step.
- **`./dev diagrams`**, new: renders all 44 Mermaid blocks in the documentation
  so a broken one is caught before it becomes an error box on GitHub. Also
  records the two traps in the `mermaid-cli` image that make every diagram look
  broken when only the image is.

## [1.9.0] - 2026-07-30

Branching workflows: pull requests build on their own branch, publication becomes
independent of what triggered a build, and the bridge stops removing builds it did
not start. See [doc/branching-workflows.md](doc/branching-workflows.md) for the
scenarios these were designed against.

### Added

- **`publishChecks`** (build feature, default on) is the *only* input to "does this
  build configuration report to GitHub?", independent of what started the build.
  The `triggerOn*` flags and the branch/path/metadata filters are the other axis:
  what the bridge starts *automatically*.

- **Branch-source PR builds** (`prBuildRef = pull | branch`, per project, default
  `pull`): PR builds run on the PR's head branch instead of the synthetic `pull/N`
  ref, so TeamCity shows a real branch name and a push builds **once**. The PR
  context is resolved from the built commit, so the PR gates keep applying.
  Requires the head branches in the VCS root's branch spec.

- **Explicit GitHub commands survive the filters.** A build enqueued from a PR
  comment, a review approval, *Re-run* or `POST /api/trigger` is stamped
  `triggerSource=command` and gated like a manual Run, so nothing removes it
  afterwards. This is what makes re-running a "Skipped: …" row work.

- **Fork pull requests are ignored** — `head.repo.full_name` is parsed now — and
  counted (`fork_events_ignored`). A blank head repo (deleted fork) fails open.

- **"Re-run all checks"** (`check_suite.rerequested`, previously answered *204
  unsupported*) re-runs every opted-in configuration for that head;
  `rerunAll.onlyFailed` restricts it to those whose last build failed. The managed
  App subscribes to `check_suite`.

- **"Branches & PRs" project tab**: queued, running and the last 30 finished builds
  per configuration, every row carrying **both** keys (branch and PR), searchable by
  either (`189`, `#189`, `Feature/`) and sortable. Backed by a `pr-189` build tag
  (`prTag.enabled`, `prTag.prefix`), so the page costs no GitHub call and TeamCity's
  tag filter finds it too; builds that ran before the PR existed are back-filled.

- **`labeled` / `unlabeled` / `edited` / `reopened` handled**: a label or a title
  edit becomes a *trigger*, not only a filter, and a reopened PR gets its builds
  back. Those three post no "Skipped" row — it would overwrite the result already
  published for that commit.

- **Artifact links** in the Check Run and the sticky comment
  (`checkRun.artifactLinks`, default on). Every link is a direct download, not the
  artifacts tab; the root URL is read per project.

- **Line-level annotations** (`checkRun.annotations`, default on): compiler
  diagnostics pinned to their file and line in the diff, GNU/clang and MSVC shapes,
  parsed from the build problems TeamCity already reports — no log scanning. Paths
  outside the checkout are skipped (GitHub rejects them), capped at GitHub's 50.

- **`skipIfCommitPassed`** (build feature, default off): an **automatic** build
  queued for a commit that already passed in that configuration is dropped and the
  earlier success republished. Matched on the commit alone, any ref. Explicit
  requests always re-run; leave it off for scheduled suites.

- **`queueCleanup.enabled`** (server-wide, default on): one switch for everything
  that takes a build *out* of the queue. Off, the bridge only adds and reports.

- **A warning when two status publishers report the same build**: a startup `WARN`
  and a self-test row (WARN, never FAIL) listing configurations carrying both this
  feature and the bundled *Commit status publisher*, template-inherited included.
  The plugin still disables nothing.

- **Branch builds are attached to their pull request** (`branchPrLookup.enabled`,
  default on): the PR is resolved from the built commit, so a manual run on a branch
  gets the PR parameters, the `draft`/`ready` tag and the sticky comment. Only open
  PRs whose head is that exact commit qualify, so an intermediate commit is never
  reported as a PR's state.

### Changed

- **The bridge no longer removes a build it did not start.** `triggerOnBranch` and
  `triggerOnPrReady` were HARD blocks that removed even a manual Run from the queue
  — clicking "Run" silently did nothing. They now mean "the bridge does not trigger
  this automatically"; cleanup is limited to two automatic cases, a scope filter or
  `skipIfCommitPassed`. Use `publishChecks` to silence a configuration on GitHub.
  A behaviour change, but never the intent of those flags: stopping a human from
  starting a build is TeamCity's job, not a reporting plugin's.

- The head branch is appended to the build number only on a `pull/N` ref; on any
  other ref the Branch column already shows it.

- The plugin declares its version from the POM, so TeamCity's plugin list cannot
  show a number unrelated to the zip.

### Fixed

- **The "Queued" Check Run appears reliably.** `buildTypeAddedToQueue` fires before
  TeamCity has collected the VCS revision, so with no head SHA the row was skipped
  silently and only showed up later as "Building". The publish is retried on
  TeamCity's scheduler until the revision resolves, and aborted if the build starts
  or leaves the queue meanwhile, so it never clobbers a more advanced row.

## [1.8.0] - 2026-06-13

Pull-request metadata gating, and a correction to the comment-trigger event.

### Added

- **PR-metadata build gate**, per build configuration: `requirePhrase` (run only if
  the PR title or body contains it), `skipPhrase` (e.g. `[skip ci]`) and
  `labelFilter` (VCS-filter rules over label names, `+:ci` / `-:no-ci`). Automatic
  triggers only — a non-matching PR gets a *"Skipped: PR metadata out of scope"*
  row, a manual Run bypasses them. Read from the webhook payload, so no extra call.

### Changed

- **Comment triggers fire on `pull_request_review_comment`** (inline diff comments)
  instead of `issue_comment`: GitHub only exposes the latter to an App holding the
  *Issues* permission, which this plugin deliberately does not request.
  `issue_comment` stays handled for operators who add that permission.

## [1.7.0] - 2026-06-12

GitHub App self-provisioning, in-product configuration, reliability hardening,
more trigger sources, an external API and a documentation overhaul.

### Added

- **One-click managed GitHub App**: create a pre-configured App from the admin page
  through GitHub's App-manifest flow — webhook URL, permissions and events filled
  in, and the App id, private key, slug and secret captured on the `state`-validated
  callback. No TeamCity OAuth connection, no `.pem` handling. A **Verify** button
  diffs the App's live permissions and events against what the plugin needs.
- **The managed App as a token source**: `connectionId = managed` mints installation
  tokens from it instead of from a TeamCity connection.
- **In-product configuration**: a per-project *GitHub Bridge* tab for the
  repository/connection/branch settings, and server-wide tuning and flags applied
  live from the admin page (no restart).
- **HTTP retry and rate-limit handling**: exponential backoff on transient failures,
  5xx and rate-limit exhaustion, honouring `Retry-After` (capped at 30 s).
- **Webhook replay protection**: a redelivered `X-GitHub-Delivery` is acknowledged
  but not re-processed (LRU + 24 h TTL).
- **`/health` and `/metrics`**: a JSON liveness snapshot and Prometheus counters
  (webhooks, check runs, enqueues).
- **`pull_request.closed`/merged**: cancels builds still queued for that head.
- **Monorepo path filtering** (`pathFilter`): enqueue only when the PR's changed
  files match.
- **Run on PR approval** (`runOnApproval`), on `pull_request_review`.
- **Re-run from GitHub's Checks UI** (`check_run.rerequested`).
- **Comment-triggered builds** (`commentTrigger`), restricted to trusted commenters
  by GitHub `author_association` (`OWNER,MEMBER,COLLABORATOR` by default).
- **Authenticated external API** under `/api/` (bearer token, constant-time
  compared, disabled without a token): `GET status`, `events`, `metrics`, and
  `POST trigger`.
- **Repository allowlist and dry-run mode**, build-failure details in the Check Run
  body, an optional sticky PR summary comment (off by default), and opt-in legacy
  `teamcity.pullRequest.*` parameter aliases.

### Fixed

- `PrInfoCache` no longer serves a stale PR forever after a failed refresh: stale
  entries are bounded by a grace window, then dropped.
- Installation-token expiry is parsed strictly; a missing `expires_at` no longer
  fabricates an hour off the wall clock.
- The webhook endpoint bounds the body it reads (25 MB → 413) *before* verifying the
  signature.
- `AppTokenMinter` consults its cache before signing a JWT and listing
  installations.
- `PluginSettingsStorage` writes are serialised, fixing a concurrent-write race.

### Changed

- **Admin page guided**: a "Getting started" checklist leads, the GitHub App card is
  the primary action, self-tests moved below configuration. The project and build
  feature forms explain what belongs where.
- **Documentation overhaul**: a 5-minute [Quickstart](doc/quickstart.md), a
  [doc index](doc/README.md), `usage-scenarios.md` and `troubleshooting.md` rewritten
  onto the build-feature opt-in model, one canonical set of App permissions/events.
- Internal: one HTTP helper in `GitHubClient`, `RsaKeyParser` and `AppJwt`
  extracted, shared `RequestUrlBuilder` and Check Run naming.

## [1.6.0] - 2026-06-02

Two operator-reported correctness fixes.

### Fixed

- **A template-inherited "GitHub Bridge integration" feature is recognised.** The
  reader used `getBuildFeaturesOfType`, which returns only features attached
  *directly*, so a configuration inheriting the feature from a template was invisible
  to the plugin — not enqueued on PR events, no Check Run. It now reads
  `resolvedSettings`, the enabled-and-resolved feature set, so a template-only opt-in
  counts exactly like a local one.

- **A PR build that left the queue without running no longer stays stuck at
  "Queued".** `buildRemovedFromQueue` fires for *every* queue exit, including the
  build starting, so the handler's "null user means cancelled" assumption left a
  failed-to-start build (typically a failed snapshot dependency) queued forever. Each
  case now reaches a terminal state: started → left to `buildStarted`/`buildFinished`;
  own finished record → its real outcome, red; optimised into an equivalent build →
  left to that build; removed by a user → *"Cancelled before start"*; system removal
  with no record → silent, so it cannot overwrite a real result. `buildFinished` also
  falls back to the promotion's revisions when a failed-to-start build carries none,
  so its head SHA still resolves.

[1.6.0]: ../../releases/tag/1.6.0

## [1.5.0] - 2026-05-27

**Breaking.** Opt-in moved from BuildType parameters to a build feature; every
configuration key was renamed. Setups built against 1.4.0 need the migration below.

### Added

- **"GitHub Bridge integration" build feature** (`github-bridge`), with an edit form
  that validates the branch-spec syntax: its *presence* on a build configuration is
  the opt-in, and its fields (`triggerOnBranch`, `triggerOnPrReady`,
  `triggerOnPrDraft`, plus two branch-list overrides) are the per-task settings. The
  mandatory repository/connection settings live on the project.

- **Two independent trigger paths per project**: *branch trigger* (non-PR branches)
  and *PR trigger* (PR events), each with its own enable toggle and branch list
  (`branchTrigger.*`, `prTrigger.*`), each overridable per configuration.

- **One gating decision, four call sites.** `BridgeGate.decide` centralises HARD vs
  SOFT gating — the trigger flags block even a manual Run, the branch lists do not —
  and the listener, the queue cleaner, the start-build filter and the publisher all
  delegate to it, so they cannot disagree. (1.9.0 later dropped the HARD semantics:
  the bridge no longer removes a build a human started.)

- **`BridgeFeatureReader`**, the single source of truth for a resolved
  configuration. It reads project parameters through
  `InheritableUserParametersHolder.getParameters()`, since `buildType.parameters`
  does not include project-chain inheritance on TC 2026.1.

- **`BranchSpecMatcher`**: a pure parser and matcher for TeamCity branch specs
  (`+:`/`-:` per line, glob by default, explicit `/regex/`).

- **`PullRequestEventListener`** (was `ReadyForReviewListener`) handles `opened`,
  `ready_for_review` and `synchronize` in one place, so a PR opened directly as ready
  builds on its initial SHA and every push to a ready PR refreshes the Check Runs.

- **Smart-skip on existing builds**: before enqueueing, a running, queued or
  recently-finished build at the same `(ref, head SHA)` means the webhook retry is
  dropped. Bounded to the 50 most recent finished builds.

- **Skipped Check Runs from the listener**: *"Skipped: draft PR"* and *"Skipped:
  branch out of scope"*, idempotent per `(sha, build configuration)`. Non-PR
  suppression stays silent.

- **A verbose diagnostic when no candidate is found**, logging every
  build-type-collection accessor plus one line per configuration carrying the
  feature and why it was rejected.

### Fixed

- Project-chain parameters resolve at all (1.4.x read them where TC 2026.1 does not
  expose inheritance).
- The listener runs under `runAsSystemUnchecked`: `ProjectManager`'s accessors filter
  by current user, and a webhook delivery has none — so the listener used to see zero
  build configurations.
- Repository slugs compare case-insensitively; GitHub echoes canonical casing, the
  DSL author's value may differ. This is what made "PRs opened directly as ready
  trigger nothing".
- `setBuildComment` no longer fails the enqueue on TC 2026.1 (the comment moved into
  `addToQueue`'s trigger-source argument, which is what "Triggered by" shows).
- The listener invalidates `PrInfoCache` before enqueueing, so the queue cleaner
  cannot refetch a stale `draft: true` and drop the build just enqueued.

### Removed

The BuildType-parameter opt-in path in full: `repo` / `connectionId` /
`ignoreDrafts` are no longer read per configuration, `prScanEnabled` became
`prTrigger.enabled`, `branchFilter` split into `branchTrigger.branches` +
`prTrigger.branches`, `ignoreDrafts` became the inverted `triggerOnPrDraft`, and
`branchFilterOverride` split into the two per-configuration overrides.

### Migration from 1.4.0

Move `teamcity.github.bridge.repo` and `connectionId` to the **project**, then add
the *GitHub Bridge integration* build feature to each participating build
configuration (all three trigger flags default to `true`):

```kotlin
project {
    params {
        param("teamcity.github.bridge.repo", "owner/name")
        param("teamcity.github.bridge.connectionId", "PROJECT_EXT_42")
    }
}

buildType {
    features { feature { type = "github-bridge" } }
}
```

Legacy per-configuration parameters are silently ignored after the upgrade; the
admin self-test says so ("No buildType has the … build feature configured").

[1.5.0]: ../../releases/tag/1.5.0

## [1.4.0] - 2026-05-27

### Added

- **`pull_request.opened` and `synchronize` handled**, not just `ready_for_review`:
  a PR opened directly as ready builds on its initial SHA, and every push to a ready
  PR refreshes the Check Runs. This is what lets DSL authors drop VCS triggers on
  opted-in configurations — the source of the "cancelled" traces operators
  complained about. The draft flag comes from the payload, so the no-op path for
  drafts costs no token.

### Changed

- `ReadyForReviewListener` → **`PullRequestEventListener`**, dispatching on the
  action (log prefix and the `grep` recipe in `troubleshooting.md` change with it).
  `parseReadyForReview` → `parsePullRequestEvent`, returning the action and the draft
  flag. The on-the-wire contract is unchanged.

### Fixed

- The listener invalidates `PrInfoCache` *before* enqueueing on every action, so the
  queue cleaner cannot refetch a stale `draft: true` and drop the build just
  enqueued on a freshly-ready PR.

[1.4.0]: ../../releases/tag/1.4.0

## [1.3.0] - 2026-05-26

Every PR build transition reaches GitHub, "Details" jumps to the build, and a
manual trigger always runs.

### Added

- **The full Check Run lifecycle**: `queued` on `buildTypeAddedToQueue`,
  `in_progress` on start, `cancelled` on interrupt and on a user-removed queued
  build, `completed` on finish. GitHub dedups by `(name, head_sha)`, so one row
  transitions through all of them — no more rows stuck at "in_progress" after a
  manual stop.
- **`details_url` on every Check Run**, jumping to the TeamCity build page (the
  configuration page for skipped and queue-cancelled rows). Falls back silently when
  the server root URL is unset.
- **A manual Run bypasses draft suppression** instead of being silently removed from
  the queue. VCS and dependency triggers keep the existing behaviour.

### Changed

- `PrPromotionTagger` no longer requires `ignoreDrafts=true`: it uses the same opt-in
  gate as the publisher, so ALL-scope PR builds get the `draft`/`ready` tag too.

### Fixed

- A draft-suppressed build no longer races with its own `queued` row and stays stuck
  there: the publisher yields it to the "Skipped: draft PR" row.

[1.3.0]: ../../releases/tag/1.3.0

## [1.2.0] - 2026-05-26

### Added

- **Self-minted installation tokens.** The plugin signs an RS256 JWT with the App's
  private key and calls `POST /app/installations/{id}/access_tokens` itself
  (`AppTokenMinter` + `AppTokenCache`), so it works on a vanilla TC 2026.1 sandbox:
  no "Test connection" click to seed the cache, no dummy `commitStatusPublisher`
  build feature kept alive as a workaround.
- **GitHub Enterprise support.** Every REST call targets the API base derived from
  the connection descriptor's GitHub URL (`<host>/api/v3` for GHE). It was hardcoded
  to `api.github.com`, which broke self-mint on GHE at the installation lookup.
- **A tolerant PEM parser** for the App key: PKCS#1 and PKCS#8, literal `\n`
  escapes, a PEM squashed onto one line (which is how TeamCity stores it), raw
  base64 as a last resort. Parse failures are diagnosed by category (encrypted,
  OpenSSH, EC/DSA, truncated) — the BEGIN line is logged, the body never.
- Self-tests report the API base actually used, and treat any HTTP answer to the
  unauthenticated probe as reachability (GHE answers 401/403).
- New dependency `com.auth0:java-jwt` (~64 KB, no BouncyCastle): PKCS#1 keys are
  wrapped to PKCS#8 in-process instead of pulling a crypto stack.

### Changed

- `TokenResolver.resolveAccessToken` takes the repository and returns a
  `ResolvedAccess` (token **and** API base) instead of a bare string, so the base
  travels with the token to every caller. Minted tokens are scoped to the
  installation matching the repository owner, case-insensitively.
- Descriptor keys match what TC 2026.1 actually stores (`gitHubApp.appId`,
  `secure:gitHubApp.privateKey`, `gitHubApp.ownerUrl`), with the historical
  spellings kept as fallbacks.

### Removed

- The `OAuthTokensStorage.getProjectTokens` cache-only fallback: TC 2026.1 does not
  refresh App tokens reliably, so it served 401-rejected stale tokens that masked the
  real configuration. Self-mint replaces it.

Resolution order is self-mint first, `ProjectConnectionCredentialsManager` second as
a forward-compatibility hook. Minted tokens are cached per installation with a
10-minute margin under GitHub's 60-minute lifetime.

[1.2.0]: ../../releases/tag/1.2.0

## [1.0.0] - 2026-05-26

First public release, validated end-to-end against a live TeamCity server
(19/19 self-tests). Note that most of what follows was reshaped by later
versions — 1.5.0 replaced the parameter opt-in with a build feature, and 1.9.0
redrew the trigger/publication model.

### Opt-in and published parameters

Per build configuration, three parameters: `ignoreDrafts`, `repo` (`owner/name`)
and `connectionId` (a TeamCity GitHub App connection external id or its token
storage id). Every opted-in build then sees eight read-only parameters —
`isPullRequest`, `isDraft`, and `pullRequest.{number,title,author,sourceBranch,
targetBranch,headSha}` — always defined (empty on a non-PR branch), so a DSL
condition never hits an unresolved parameter.

### Inbound

`POST /app/teamcity-github-bridge/webhook` takes signed App webhooks. HMAC-SHA256
over the raw body is mandatory and **fail-closed**: a missing or invalid signature
is a 401 before the payload is parsed. `pull_request.ready_for_review` re-enqueues
every matching configuration, `ping` answers `200 pong`, anything else `204`. No
replay protection yet.

### Outbound

- Draft-PR builds are removed from the queue at enqueue time, with
  `DraftAwareBuildFilter` as the safety net when the PR state cannot be resolved in
  time, and a `conclusion=skipped` Check Run so the PR shows a deliberate skip
  instead of "Expected — Waiting for status".
- A Check Run on `buildStarted` and `buildFinished`, its conclusion mapped from
  TeamCity's status. `output.summary` carries `statusDescriptor.text`, so an agent's
  `##teamcity[buildStatus text='…']` survives the trip to GitHub.
- The `draft` / `ready` tag on the promotion at enqueue time, re-styled as coloured
  pills in the build lists by a client-side CSS overlay, and the source branch
  appended to the build number.

### Token acquisition

Two-tier: `ProjectConnectionCredentialsManager` first, then the cache-only
`OAuthTokensStorage` fallback. Tokens are opaque end-to-end, so the ~520-character
stateless `ghs_*` format works untouched. Warnings are rate-limited per (project,
connection) to avoid flooding the log on a misconfigured server.

### Operations

- Admin page under *Administration → Server Administration → GitHub Bridge*: status,
  the HMAC secret form (CSRF-protected), the last 100 webhook deliveries, and a
  one-click self-test battery over seven categories (secret, log file, API
  reachability, HMAC round-trip, webhook self-delivery, token resolution, API auth).
- The webhook secret can come from the admin page, the plugin settings file or —
  legacy — `internal.properties`.
- A dedicated log file at `<TC_DATA_DIR>/logs/teamcity-github-bridge.log`, rotated
  at 10 MB × 10.
- `GET /info` and `/info.md` return a live configuration snapshot, ready to paste
  into the GitHub App settings.

### Compatibility

TeamCity Server 2026.1 (build 222521) or newer, Java 21. The bundled
`commit-status-publisher` and `pullRequests` plugins can stay enabled alongside
this one; see [configuration.md](doc/configuration.md) for the operating models.

[1.0.0]: ../../releases/tag/1.0.0
