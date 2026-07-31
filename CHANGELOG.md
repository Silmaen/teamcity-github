# Changelog

All notable changes to this project are documented in this file.
The format follows [Keep a Changelog](https://keepachangelog.com/)
and the project adheres to [Semantic Versioning](https://semver.org/).

## [1.10.0] - unreleased

### Added

- **An infrastructure failure is told apart from a broken build**
  (`checkRun.infraNeutral`, default on). Everything that was not green used to
  become `failure`, so a lost checkout, an unresolvable artifact dependency or a
  runner that could not start turned a pull request red exactly like a failing
  test — the reviewer could not tell "your code is broken" from "our CI broke",
  which is the difference between fixing a commit and re-running a build. The
  build's problems are now classified from their type (TeamCity's own
  internal-error set), and an infrastructural failure:

  - says so in the title — *"Infrastructure failure: Unable to collect changes"*;
  - opens its summary with a line stating the commit was **not** verified and
    that the build should be re-run;
  - concludes `neutral` instead of `failure`, which GitHub counts as satisfied
    for a required check, so a CI hiccup no longer blocks the merge. Turn the
    flag off to keep it red; the title names the cause either way.

  Two deliberate exceptions: one problem the build itself produced (a failing
  test, a compile error, a non-zero exit code) makes the whole failure the
  **code's**, however many infrastructure problems came with it; and a failed
  **snapshot dependency** is named — *"Build failed: Snapshot dependency
  failure"* — but stays red, because that dependency may well have failed on this
  pull request's own code. Builds that never started for a failed dependency get
  the same treatment, so their row says why instead of just "Build failed".

- **Test outcome in the Check Run** (`checkRun.testStats`, default on): the
  counts in the title GitHub shows in the merge box — *"Build failed — 3 of 1046
  tests failed (2 new)"* — and the failing tests in the body, new failures first,
  each with its duration and its failure text folded into a `<details>` block.
  Muted tests are counted apart and never listed as failures. A build that runs
  no test says nothing.

- **Timings in the Check Run summary** (`checkRun.timings`, default on):

  ```
  - **Total** — 11m 13s
  - **Run** — 7m 12s on `agent-3`
  - **Wait** — 4m 1s (dependencies 3m, free agent 1m, other 1s)
  ```

  "Run" is working time, never waiting. The dependency share runs to the instant
  the last snapshot dependency finished, so it includes the wait of the
  dependency itself, and it is shown whenever the build has dependencies. The
  agent share is only what TeamCity itself blames on agent availability — the
  dates say how long a build waited, never why — so wait that nothing explains
  is counted in the total and shown as "other".

- **`started_at` / `completed_at` are sent**, so GitHub renders the duration
  itself instead of inferring it from when our request happened to arrive.

- The Check Run body follows a fixed order: **failure cause, tests, artifacts,
  link to the build in TeamCity**.

### Fixed

- **Personal builds publish nothing** — no `queued`, no `in_progress`, no
  conclusion, no PR comment, whatever `publishChecks` says: they verify a patch
  that is not in the repository. Triggering one by hand used to leave a Check Run
  stuck on **"Queued"** for good. They are also outside the queue dedup both ways
  (they never cover a commit, and the bridge never removes them), while still
  resolving their pull request: parameters, tags, retro-association.

- **Two lag bugs at `buildFinished`**, where TeamCity has not finished
  transitioning the build: `finishDate` is null (every build reported "Ran <1s",
  and no `completed_at`) and the status descriptor still reads "Running" (a green
  Check Run summarised "Running"). Both are handled.

- **`draft` / `ready` pills are actually coloured**: `ready` green, `draft` a
  neutral grey, dark theme included. The colour now lands on the **chip**
  (Ring UI's `.ring-tag-container`) instead of the label inside it — painting the
  label put a coloured rectangle inside Ring's grey chip. The `pr-189` tag is
  left as TeamCity renders it: the Branch column is narrow.

### Housekeeping

- Tests moved out of `src/` to a top-level `test/kotlin/`, same packages.
- Twelve leaf packages became seven: `cache` → `api`, `filter` → `queue`,
  `parameters` → `enrich`, `selftest` (with `PublisherConflictReporter`) →
  `config`, `retrigger` → `web`. Each held a single class. Base package
  unchanged — the Spring XML depends on every fully-qualified name.
- Dead code removed: four declarations with no caller; a sweep of 1169 found
  nothing else.
- `doc/roadmap.md` holds only future work now, with the next ideas written down
  (cancelling superseded builds, flaky tests, coverage trend, retrying an
  infrastructure failure).
- **Linking a TeamCity page to the pull request is dropped.** A tag *is* a
  filter and the React pages win the click; `PlaceId.BUILD_SUMMARY` /
  `BUILD_ACTIONS` render on the classic build page only. The reasoning is kept
  in the code so the next attempt starts from what is known.

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
