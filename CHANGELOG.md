# Changelog

All notable changes to this project are documented in this file.
The format follows [Keep a Changelog](https://keepachangelog.com/)
and the project adheres to [Semantic Versioning](https://semver.org/).

## [1.8.0] - 2026-06-13

Pull-request metadata gating and a correction to the comment-trigger
event. (Intermediate patch builds were internal staging only and are not
released separately.)

### Added

- **PR-metadata build gate.** Per-build-configuration filters that trigger
  or suppress a build based on the pull request's title, description and
  labels:
  - `requirePhrase` — run only if the PR title or body contains the phrase;
  - `skipPhrase` — skip if the PR title or body contains it (e.g. `[skip ci]`);
  - `labelFilter` — VCS-filter rules over PR label names (e.g. `+:ci`,
    `-:no-ci`).

  Enforced for automatic triggers (a non-matching PR gets a *"Skipped: PR
  metadata out of scope"* Check Run); a manual *Run* always bypasses them.
  PR title/body/labels are read from the webhook payload (no extra API
  call) and from `GET /pulls/{n}` on the queue/filter paths.

### Changed

- **Comment triggers now fire on `pull_request_review_comment`** (inline
  PR diff comments) instead of `issue_comment`. GitHub only exposes the
  `issue_comment` event when the App holds the *Issues* permission, which
  the plugin deliberately does not request; `issue_comment` stays handled
  as an opt-in for operators who add that permission. The managed-App
  manifest now subscribes to `pull_request_review_comment`.

## [1.7.0] - 2026-06-12

GitHub App self-provisioning, in-product configuration, reliability
hardening, more trigger sources, an external API, and a documentation
overhaul. (Intermediate patch builds were internal staging only and are
not released separately.)

### Added

- **One-click managed GitHub App.** Create a pre-configured App straight
  from the admin page via GitHub's App-manifest flow — the webhook URL,
  permissions and events are filled in for you; on creation the App id,
  private key, slug and webhook secret are captured automatically
  (callback `/app/teamcity-github-bridge/app-callback`, `state`-validated)
  and stored in the plugin settings, with no TeamCity OAuth connection or
  `.pem` handling required. A **Verify** button calls `GET /app` and diffs
  the App's live permissions/events against what the plugin needs.
- **Plugin-managed App as a token source.** Set a build configuration's
  `connectionId` to `managed` to mint installation tokens from the
  created App instead of a TeamCity connection.
- **In-product configuration pages.** A per-project *GitHub Bridge* tab
  edits the project-level repository/connection/branch settings; the admin
  page edits server-wide tuning + feature flags live (no restart) via
  `BridgeServerSettings.applyTo`.
- **HTTP retry + GitHub rate-limit handling.** Every GitHub call retries
  transient failures, 5xx, and rate-limit exhaustion with exponential
  backoff that honours `Retry-After` (capped at 30 s).
- **Webhook replay protection.** Redelivered payloads (same
  `X-GitHub-Delivery`) are acknowledged but not re-processed
  (`DeliveryReplayGuard`, LRU + 24 h TTL).
- **`/health` and `/metrics` endpoints.** A JSON liveness snapshot and a
  Prometheus-format counter set (webhooks, check runs, enqueues).
- **`pull_request.closed`/merged handling.** Cancels builds still queued
  for the PR's head.
- **Monorepo path filtering.** A per-build-configuration `pathFilter`; the
  listener only enqueues a build when the PR's changed files match.
- **Run on PR approval.** A per-build-configuration `runOnApproval` flag
  enqueues the build on `pull_request_review` (approved).
- **Re-run from GitHub.** Handles `check_run` `rerequested` to re-enqueue
  the matching build configuration from the Checks UI.
- **Comment-triggered builds.** A per-build-configuration `commentTrigger`
  phrase; a PR comment containing it enqueues the build, restricted to
  trusted commenters (GitHub `author_association` allowlist —
  `OWNER,MEMBER,COLLABORATOR` by default). Handles the `issue_comment`
  webhook event.
- **Authenticated external HTTP API** under
  `/app/teamcity-github-bridge/api/` (bearer token, constant-time
  compared; no token = disabled): `GET status`, `GET events`,
  `GET metrics`, and `POST trigger` to enqueue a build.
- **Repo allowlist and dry-run mode** (server settings).
- **Build-failure details** surfaced in the Check Run body (`output.text`).
- **Optional sticky PR summary comment** (off by default; needs the App's
  pull-requests write permission).
- **Legacy `teamcity.pullRequest.*` parameter aliases** (opt-in) to ease
  migration off the bundled `pullRequests` feature.

### Fixed

- `PrInfoCache` no longer serves a stale PR indefinitely after a fetch
  failure — stale entries are bounded by a grace window then dropped.
- Installation-token expiry is parsed strictly; a missing `expires_at` no
  longer fabricates an hour off the wall clock (the minter applies a
  conservative fallback against its injected clock).
- The webhook endpoint bounds the request body it reads (25 MB / 413)
  before verifying the signature.
- `AppTokenMinter` consults the token cache before signing a JWT and
  listing installations (owner → installation-id mapping).
- `PluginSettingsStorage` writes are serialised, fixing a concurrent-write
  race on the temp file.

### Changed

- **Guided admin page**: a "Getting started" checklist leads the page, the
  GitHub App card is the primary action, and self-tests moved below
  configuration.
- **Project settings page** documents `connectionId=managed`, requires
  repository + connection (client- and server-side validation), and points
  per-build-configuration options to the build feature.
- **Build feature form** explains the hard/soft gate distinction and
  points to the project's GitHub Bridge tab.
- **Documentation overhaul**: added a 5-minute [Quickstart](doc/quickstart.md)
  and a [doc index](doc/README.md); rewrote `usage-scenarios.md` and
  `troubleshooting.md` onto the current build-feature opt-in model; led the
  webhook/installation docs with the managed-App path; reconciled the
  GitHub App permissions/events into one canonical set.
- Internal refactors: a single HTTP helper in `GitHubClient`, extracted
  `RsaKeyParser` and `AppJwt`, shared `RequestUrlBuilder` and Check Run
  naming.

## [1.6.0] - 2026-06-02

Two operator-reported correctness fixes: opt-in BuildTypes that
inherit the feature from a template were invisible, and PR builds that
never produced a result stayed stuck at "Queued" on GitHub.

### Fixed

- **Template-inherited "GitHub Bridge integration" feature is now
  recognised.** `BridgeFeatureReader` resolved the feature via
  `buildType.getBuildFeaturesOfType(...)`, which returns only the
  features attached *directly* to a BuildType. A BuildType that
  inherits the `github-bridge` feature from a BuildType template
  (without re-declaring it locally) was therefore never seen by the
  plugin: it was not enqueued on PR events and never received a Check
  Run — the symptom being PR-normal BuildTypes that "did not show up at
  all" when a PR was opened directly as ready. The reader (and the
  listener's diagnostic scan) now read through
  `buildType.resolvedSettings` — the *enabled and resolved* feature
  set, with templates applied and disabled features removed — so
  template-only opt-ins are honoured exactly like locally-attached
  ones.

- **PR builds that left the queue without running no longer stay stuck
  at "Queued".** A build that "failed to start" — most commonly because
  a snapshot dependency failed — used to keep its "Queued" Check Run
  forever, because the publisher's `buildRemovedFromQueue` handler
  returned early on a null user (`buildRemovedFromQueue` fires for every
  queue exit, including the build *starting*, so a null user does not
  mean "cancelled"). The handler now drives the Check Run to a terminal
  state for every relevant case:
  - the build started running → left to `buildStarted` / `buildFinished`;
  - it has its own finished record (failed to start) → reported with its
    real outcome, i.e. **"Build failed"** (red, blocks the merge);
  - it was optimised into an equivalent build → left to that build;
  - a user removed it → **"Cancelled before start"**;
  - a system removal with no record (a duplicate/optimised promotion of
    a shared dependency in a build-chain fan-out being torn down) →
    silent, so it cannot overwrite the real build's result.

  `buildFinished` also now falls back to the promotion's revisions when
  a failed-to-start build carries none of its own, so its head SHA still
  resolves. Net effect in a fan-out where every BuildType depends on one
  build that fails: that build and all of its dependents reach
  **"Build failed"**, nothing stays stuck, and no lifecycle event
  overwrites another's result.

[1.6.0]: ../../releases/tag/1.6.0

## [1.5.0] - 2026-05-27

Operator-feedback release. Three themes:

1. **UI-driven, per-task configuration.** The legacy
   `teamcity.github.bridge.repo` / `connectionId` / `ignoreDrafts`
   BuildType parameters are gone. Opt-in is now a "GitHub Bridge
   integration" Build Feature on each participating BT, backed by
   four project-level parameters for the mandatory config. The
   feature exposes per-task trigger flags and branch-list
   overrides with a dedicated edit form.

2. **Two independent trigger paths per project.** The plugin
   separates *branch trigger* (builds on non-PR branches) from
   *PR trigger* (builds on PR events). Each has its own enable
   toggle and its own branch list, both individually overridable
   per BT.

3. **HARD vs SOFT gating, with predictable manual triggers.**
   Per-BT trigger flags are HARD: even a manual operator click is
   blocked when the flag is off. Branch lists are SOFT: manual
   triggers bypass them. The decision is centralized in
   `BridgeGate.decide`, used by the listener, the queue cleaner,
   the start-build filter, and the publisher's draft-suppression
   heuristic — provably consistent across all four sites.

**Breaking change.** All configuration keys are renamed; setups
built against v1.4.0 require manual migration. See below.

### Configuration model

**Project-level parameters** (4 keys, in addition to the already-existing
`teamcity.github.bridge.repo` + `connectionId`):

| Key | Default | Meaning |
|---|---|---|
| `teamcity.github.bridge.branchTrigger.enabled` | `true` | Plugin participates on non-PR branch builds (main, Release/*, …) |
| `teamcity.github.bridge.branchTrigger.branches` | empty=all | TC branch spec (`+:`/`-:` per line) for non-PR branches |
| `teamcity.github.bridge.prTrigger.enabled` | `true` | Plugin participates on PR events |
| `teamcity.github.bridge.prTrigger.branches` | empty=all | TC branch spec matched against PR source branch (headRef) |

**BuildType build feature** `github-bridge` (5 fields):

| Field | Default | Meaning |
|---|---|---|
| `triggerOnBranch` | `true` | HARD: this BT runs on non-PR branches |
| `triggerOnPrReady` | `true` | HARD: this BT runs on ready PRs |
| `triggerOnPrDraft` | `true` | HARD: this BT also runs on draft PRs. Requires `triggerOnPrReady=true`. |
| `branchTriggerBranchesOverride` | empty | REPLACES project's non-PR branch list |
| `prTriggerBranchesOverride` | empty | REPLACES project's PR source-branch list |

### HARD vs SOFT semantics

- **HARD blocks** (the three `triggerOnXxx` flags + project
  `xxxTrigger.enabled` toggles): even manual operator triggers
  cannot bypass. The build is suppressed silently — no GitHub
  Check Run.
- **SOFT blocks** (the branch lists, project or BT override):
  manual triggers bypass them. Auto builds for excluded PRs post
  a "Skipped: branch out of scope" Check Run; auto builds for
  excluded non-PR branches are suppressed silently (no Check Run
  on non-PR contexts).

### Gating decision matrix

| Context | Flag (HARD) | Branch list (SOFT) | Trigger | Outcome |
|---|---|---|---|---|
| Non-PR branch | ON | match | any | RUN |
| Non-PR branch | ON | no match | auto | suppress silent |
| Non-PR branch | ON | no match | manual | RUN (SOFT bypass) |
| Non-PR branch | OFF | — | any | suppress silent (HARD) |
| PR ready | ON | match | any | RUN |
| PR ready | ON | no match | auto | suppress + **Skipped: branch out of scope** |
| PR ready | ON | no match | manual | RUN (SOFT bypass) |
| PR ready | OFF | — | any | suppress silent (HARD) |
| PR draft | `triggerOnPrDraft=ON` | match | any | RUN |
| PR draft | `triggerOnPrDraft=ON` | no match | auto | suppress + **Skipped: branch out of scope** |
| PR draft | `triggerOnPrDraft=ON` | no match | manual | RUN (SOFT bypass) |
| PR draft | `triggerOnPrDraft=OFF`, Ready=ON | — | auto | suppress + **Skipped: draft PR** |
| PR draft | `triggerOnPrDraft=OFF`, Ready=ON | — | manual | suppress silent (HARD on draft) |
| PR draft | Ready=OFF | — | any | suppress silent (HARD, not for PRs) |

### Added

- **`PullRequestEventListener`** (renamed from
  `ReadyForReviewListener`) reacts to `pull_request.opened`,
  `ready_for_review`, AND `synchronize` in a unified handler. A
  PR opened directly as ready gets builds on its initial SHA;
  every subsequent push to a ready PR refreshes Check Runs on
  the new head.
- **"GitHub Bridge integration" Build Feature**
  (`GitHubBridgeBuildFeature`) with edit form
  (`bridgeFeatureEdit.jsp`) under the BuildType editor's *Build
  Features* tab. Form validates the branch-spec syntax and the
  `triggerOnPrDraft=true` ⇒ `triggerOnPrReady=true` constraint.
- **`BridgeGate.decide(config, branchName, prDraft, prHeadRef, isManualTrigger): GateDecision`**
  — centralized gating helper. The listener, the queue cleaner,
  the start-build filter and the publisher's
  draft-suppression check all delegate to it.
- **`BridgeFeatureReader.read(buildType)`** /
  **`fromInputs(projectParams, featureParams)`** — single source
  of truth for the resolved per-BT config. Reads via
  `buildType.project.parameters` (the documented
  `InheritableUserParametersHolder.getParameters()` inheritance
  path), since `buildType.parameters` /
  `buildType.parametersProvider.all` don't include project-chain
  inherited params on TC 2026.1.
- **`BranchSpecMatcher`** — pure parser + matcher for TC-style
  branch specs (`+:`/`-:` per line, glob-by-default, explicit
  `/regex/` form). Used in both project lists and BT overrides.
- **Smart-skip on existing builds.** Before enqueueing, the
  listener checks each candidate BT for an already running,
  queued, or recently finished (non-canceled) build at the same
  `(pull/N, head SHA)` coordinate. Same-SHA duplicates from
  rapid webhook retries are skipped with a log line. Bounded
  history scan (50 most recent finished builds).
- **`SecurityContextEx.runAsSystemUnchecked { … }`** wrapper
  around the listener body. `ProjectManager`'s collection
  accessors filter by current user; a webhook delivery has no
  authenticated user, so the listener saw zero BuildTypes
  without this. Other adapter-based listeners
  (`BuildStatusCheckRunPublisher`, `PrPromotionTagger`, …) get a
  security context from TC and don't need the wrapper.
- **Case-insensitive repo slug matching** —
  `PullRequestEventListener.findCandidateBuildTypes` compares
  slugs with `equals(ignoreCase = true)`. GitHub echoes the
  canonical casing in webhook payloads; the DSL author's value
  may differ.
- **Skipped Check Runs from the listener path.** Two `SkipReason`
  values are surfaced on GitHub: `DRAFT_PR` ("Skipped: draft PR")
  when an opt-in BT skips drafts and the PR is draft;
  `BRANCH_FILTER` ("Skipped: branch out of scope") when a BT's
  PR branch list excludes the PR's source. Idempotent via a
  per-(sha, BT) dedup set. Non-PR contexts post no Check Run on
  suppression (silent).
- **Verbose diagnostic on empty candidate lists.** When the
  listener finds zero matching BuildTypes it logs counts from
  every BT-collection accessor (`allBuildTypes`,
  `activeBuildTypes`, `rootProject.buildTypes`,
  `projects.flatMap(ownBuildTypes)`, `numberOfBuildTypes`) plus
  one INFO line per BT carrying the feature, showing whether
  the config resolved and why each candidate was rejected.

### Changed

- All nine BT-parameter consumers refactored to read via
  `BridgeFeatureReader.read`: `PullRequestEventListener`,
  `DraftAwareBuildFilter`, `DraftBuildQueueCleaner`,
  `PrPromotionTagger`, `BuildStatusCheckRunPublisher`,
  `DraftCheckRunReporter`, `PrBuildEnricher`,
  `PrParameterProvider`, `PluginSelfTester`.
- `DraftAwareBuildFilter`, `DraftBuildQueueCleaner`,
  `PullRequestEventListener`, and
  `BuildStatusCheckRunPublisher.willBeSuppressed` all delegate
  to `BridgeGate.decide`. Provably consistent across the four
  sites — the gate's return value drives both the listener's
  bucket selection and the filter/cleaner's suppression.
- `DraftCheckRunReporter` is no longer a `BuildServerAdapter`
  listener; it became a pure service. The
  `buildTypeAddedToQueue` path is owned by
  `DraftBuildQueueCleaner` now (which suppresses and posts the
  Skipped Check Run in one place).
- Enqueue path drops `BuildCustomizer.setBuildComment(...)` —
  it throws on TC 2026.1 when the customizer was created with a
  null user. The comment moved into the `addToQueue(promotion,
  triggerSource)` second argument, surfaced in the build's
  "Triggered by" field as
  `teamcity-github-bridge: pull_request.<action> on PR #<n>`.
- Project tree walk for the listener uses
  `projectManager.rootProject.buildTypes` (recursive) with a
  fallback to `projectManager.projects.flatMap { it.ownBuildTypes }`.
  `projectManager.allBuildTypes` returned empty even under
  `runAsSystem` on the user's TC 2026.1 sandbox in some
  conditions; the manual walk is defence in depth.

### Fixed

- Project-chain `teamcity.github.bridge.*` parameters resolve
  correctly (the v1.4.x model attempted to read them via
  `buildType.parameters`, which doesn't include project
  inheritance on TC 2026.1).
- `ProjectManager` collection accessors no longer return empty
  in the listener context (security-context fix via
  `runAsSystemUnchecked`).
- `setBuildComment` no longer fails the enqueue on TC 2026.1.
- The "PRs opened directly as ready trigger 0 builds for the
  READY_ONLY cohort" symptom (case-sensitive slug compare in
  `findCandidateBuildTypes`).
- `PullRequestEventListener` invalidates `PrInfoCache` before
  enqueueing; without this,
  `DraftBuildQueueCleaner.buildTypeAddedToQueue` could refetch
  a stale entry showing `draft: true` and drop the build we just
  enqueued on a freshly ready PR.

### Removed

- The whole BT-parameter opt-in path: there is no
  `teamcity.github.bridge.repo` / `connectionId` /
  `ignoreDrafts` read on individual BuildTypes anymore. The
  matching project-level keys are read instead.
- `teamcity.github.bridge.prScanEnabled` (project) → replaced by
  `prTrigger.enabled`.
- `teamcity.github.bridge.branchFilter` (project) → split into
  `branchTrigger.branches` and `prTrigger.branches`.
- The per-BT `ignoreDrafts` toggle → replaced by inverted
  `triggerOnPrDraft` (default `true`; check off to skip drafts).
- The per-BT `branchFilterOverride` → split into
  `branchTriggerBranchesOverride` and
  `prTriggerBranchesOverride`.
- `WebhookPayloadParser.parseReadyForReview` → replaced by
  `parsePullRequestEvent` (action + draft + the existing
  fields). Callers route on the action.
- `isOptedIn(parameters)` helpers on
  `BuildStatusCheckRunPublisher` and `PrPromotionTagger` — the
  feature presence is the opt-in.
- `DraftBuildQueueCleaner.shouldRemove(pr)` — the gate now
  decides on the full `BridgeFeatureConfig`, not just the PR's
  draft flag.

### Migration from v1.4.0

DSL — replace the legacy BT-level params with project-level
params plus a Build Feature on each opt-in BuildType:

```kotlin
project {
    params {
        param("teamcity.github.bridge.repo", "owner/name")
        param("teamcity.github.bridge.connectionId", "PROJECT_EXT_42")
        // optional toggles:
        // param("teamcity.github.bridge.branchTrigger.enabled", "false")
        // param("teamcity.github.bridge.prTrigger.enabled", "false")
        // optional branch lists:
        // param("teamcity.github.bridge.branchTrigger.branches", "+:main\n+:Release/*")
        // param("teamcity.github.bridge.prTrigger.branches", "+:Feature/*")
    }
}

buildType {
    features {
        feature {
            type = "github-bridge"
            // all three default to "true"; set "false" to opt out HARD:
            // param("triggerOnBranch", "false")     // manual-on-main only
            // param("triggerOnPrReady", "false")    // never run on PRs
            // param("triggerOnPrDraft", "false")    // ready PRs only
            // BT-level branch list overrides:
            // param("branchTriggerBranchesOverride", "+:hotfix/*")
            // param("prTriggerBranchesOverride", "-:*-experimental")
        }
    }
}
```

UI — set the four `teamcity.github.bridge.*` params on the
project under *Parameters*; on each opt-in BuildType under
*Build Features &rarr; Add*, pick *GitHub Bridge integration*
and tick the desired trigger flags.

After upgrade, BuildTypes that still rely on the legacy
per-BuildType `teamcity.github.bridge.*` parameters are silently
ignored. The admin self-test ("Token resolution") now reports
"No buildType has the 'GitHub Bridge integration' build feature
configured" instead of the old parameter-shaped message.

[1.5.0]: ../../releases/tag/1.5.0

## [1.4.0] - 2026-05-27

DSL-author-feedback release: the plugin now reacts to every
non-draft `pull_request` action that should refresh builds on the
PR's head SHA, unblocking the "drop VCS triggers" pattern that
eliminates the "cancelled" traces operators complained about.

### Added

- **React to `pull_request.opened` and `pull_request.synchronize`
  in addition to `ready_for_review`.** A PR opened directly as
  ready now gets builds on its initial SHA; subsequent pushes to a
  ready PR refresh Check Runs on every new head. DSL authors can
  drop VCS triggers on opt-in BuildTypes (Owl pattern:
  `disableSettings("TRIGGER_2")`; Test_CI: omit triggers on
  NON_DRAFT-scoped builds) without losing status freshness. The
  draft flag is read directly from the webhook payload, so the
  no-op path for drafts costs zero installation tokens.

### Changed

- **`ReadyForReviewListener` renamed to `PullRequestEventListener`**
  and now dispatches on the action. The Spring bean class name,
  log prefix (`Handling pull_request.<action> for ...`), and the
  `grep` recipe in `doc/troubleshooting.md` change accordingly.
  The on-the-wire contract (POST `/webhook`, HMAC, `pull_request`
  event) is unchanged.
- **`WebhookPayloadParser.parseReadyForReview` →
  `parsePullRequestEvent`.** Returns a `PrEventPayload` carrying
  `action` + `draft` in addition to the previous fields. Callers
  route on the action.

### Fixed

- `PullRequestEventListener` now invalidates `PrInfoCache` *before*
  enqueueing on every action it handles. Without this,
  `DraftBuildQueueCleaner` could refetch a stale entry showing
  `draft: true` and drop the build we just enqueued on a freshly
  ready PR — possible whenever the cache TTL had not yet expired
  since the last "is draft?" lookup.

[1.4.0]: ../../releases/tag/1.4.0

## [1.3.0] - 2026-05-26

Operator-feedback release: every PR build lifecycle transition is
now reflected on GitHub, the "Details" link jumps straight to the
TC build, and manual triggers always run.

### Added

- **Full Check Run lifecycle.** A Check Run is posted on
  `buildTypeAddedToQueue` (`queued`), `buildStarted` (`in_progress`),
  `buildInterrupted` (`cancelled`, early), `buildFinished`
  (`completed`), and `buildRemovedFromQueue` (`cancelled before
  start`, only when a user removes it). GitHub dedups by
  `(name, head_sha)`, so the same row transitions through every
  state. No more rows stuck at "in_progress" after a manual stop, or
  at "Queued" after a queue cancellation.
- **`details_url` on every Check Run** — the "Details" link now
  jumps to the actual TC build page (`WebLinks.getViewResultsUrl` for
  running/finished, `getConfigurationHomePageUrl` for skipped/queue-
  cancelled). Falls back silently to the GitHub-side page when the
  server's rootUrl is unset.
- **Manual user triggers bypass draft suppression.** Clicking "Run"
  on a build for a draft PR now actually runs the build, instead of
  being silently removed from the queue. VCS / snapshot-dependency
  triggers still follow the existing draft-suppression behaviour.

### Changed

- **`PrPromotionTagger` no longer requires `ignoreDrafts=true`.**
  Same opt-in gate as the Check Run publisher (repo + connectionId).
  ALL-scope PR builds now also carry the `draft` / `ready` tag —
  previously the guard silently dropped them.

### Fixed

- Draft-suppressed builds no longer race with the `queued` Check Run
  and stay stuck at "Queued"; `publishQueued` consults `PrInfoCache`
  and yields the row to `DraftCheckRunReporter` so "Skipped: draft
  PR" wins uncontested.

[1.3.0]: ../../releases/tag/1.3.0

## [1.2.0] - 2026-05-26

### Added

- **Self-mint installation tokens.** The plugin now mints its own
  GitHub App installation tokens from the App's private key
  (read from the TeamCity connection descriptor) using a signed
  RS256 JWT against `POST /app/installations/{id}/access_tokens`.
  Adds `AppTokenMinter` + `AppTokenCache`. The plugin now works
  on a vanilla TC 2026.1 sandbox with no prior interaction with
  the connection cache - operators no longer need to click "Test
  connection" to seed the cache, nor keep a dummy
  `commitStatusPublisher` build feature alive as a workaround.
- **GitHub Enterprise support.** Every REST call (self-mint, PR
  queries, Check Runs, /rate_limit self-test) now targets the
  apiBase derived from the connection descriptor's GitHub URL
  parameter (TC 2026.1 stores it under `gitHubApp.ownerUrl`).
  For GHE hosts the apiBase becomes `<host>/api/v3`; for
  github.com it stays `api.github.com`. Previously hardcoded to
  `api.github.com`, which broke self-mint on GHE servers because
  the installation list lookup hit the wrong host.
- **Robust PEM parser** for the App's private key:
  - Accepts both PKCS#1 (`BEGIN RSA PRIVATE KEY`) and PKCS#8
    (`BEGIN PRIVATE KEY`) headers
  - Tolerates literal `\n` escape sequences (when the key is
    pasted into a single-line text field)
  - Handles PEMs squashed onto a single line with no newlines
    between BEGIN / body / END (which is exactly how TeamCity
    stores it in the connection settings)
  - Falls back to raw base64 PKCS#8 if no PEM markers at all
  - Three diagnostic categories logged on parse failure
    (encrypted PEM, OpenSSH PEM, EC/DSA PEM, truncated body...) -
    the BEGIN line is shown, the key body is never logged
- **Improved self-tests**: the "Token resolution" detail now
  reports the apiBase actually used; "GitHub API reachable"
  uses the apiBase of the first opted-in connection (so it is
  meaningful on GHE) and treats any HTTP response as
  reachability success (including 401/403 which GHE returns for
  the unauthenticated `/zen` probe).
- New unit tests covering `AppTokenMinter` (13 tests) and
  `AppTokenCache` (7 tests) including PKCS#1, single-line PEM,
  and JWT shape verification. Total suite now 104 tests.
- New dependency: `com.auth0:java-jwt:4.4.0` (~64 KB, no
  transitive BouncyCastle). PKCS#1 PEMs are converted to PKCS#8
  in-process via a tiny ASN.1 wrapper rather than a heavier
  crypto dep.

### Changed

- `TokenResolver.resolveAccessToken(project, connectionId)` now
  takes a third argument `repo: RepoCoords` and returns a
  `ResolvedAccess` (token + apiBase) instead of a bare `String?`.
  The apiBase travels with the token to every downstream caller.
  All eight call sites in the plugin were updated
  (`BuildStatusCheckRunPublisher`, `DraftAwareBuildFilter`,
  `DraftBuildQueueCleaner`, `DraftCheckRunReporter`,
  `PluginSelfTester`, `PrBuildEnricher`, `PrParameterProvider`,
  `PrPromotionTagger`). Tokens minted via the self-mint path are
  scoped to the installation that matches `repo.owner`
  (case-insensitive), matching how GitHub maps installations to
  repos.
- `GitHubClient.getPr`, `GitHubClient.postCheckRun` and
  `PrInfoCache.get` now accept an `apiBase` parameter (default
  `api.github.com`).
- Descriptor candidate keys for the App credentials updated to
  match what TC 2026.1 actually stores: `gitHubApp.appId`,
  `secure:gitHubApp.privateKey`, `gitHubApp.ownerUrl` are tried
  first; the unprefixed historical spellings (`appId`,
  `secure:privateKey`, `gitHubUrl`) are kept as fallbacks.

### Removed

- The `OAuthTokensStorage.getProjectTokens` cache-only fallback
  has been removed from the resolution chain. Field-testing
  showed TC 2026.1 does not refresh GitHub App tokens reliably,
  so the cache ended up returning 401-rejected stale tokens
  that masked the real configuration. Self-mint replaces it
  cleanly.

### Notes

- Resolution order is `AppTokenMinter` first (always tried),
  `ProjectConnectionCredentialsManager` second (forward-compat
  hook for a future TC fix). Tokens minted by the plugin are
  cached against the installation ID with a 10 minute safety
  margin under the GitHub-side 60 minute lifetime, so the cache
  never serves a token that is about to expire mid-call.

[1.2.0]: ../../releases/tag/1.2.0

## [1.0.0] - 2026-05-26

First public release. The plugin is feature-complete for the
original scope (TeamCity 2026.1 + GitHub App integration) and has
been validated end-to-end against a live TeamCity server with the
in-product self-test battery (19/19 PASS).

### Build configuration parameters (opt-in, per buildType)

Set these on a buildType or a shared template to enable the
plugin's behaviour for that buildType:

- `teamcity.github.bridge.ignoreDrafts` (`true`/`false`)
- `teamcity.github.bridge.repo` (`owner/name`)
- `teamcity.github.bridge.connectionId` (the TeamCity GitHub App
  connection externalId, e.g. `PROJECT_EXT_42`, or its
  tokenStorageId `CID_<hash>` - both are accepted)

### Published build parameters

Every opted-in build sees 8 read-only parameters that DSL
conditions and script steps can consume:

| Parameter | Type | Source |
|---|---|---|
| `teamcity.github.bridge.isPullRequest` | boolean | branch name |
| `teamcity.github.bridge.isDraft` | boolean | GitHub API |
| `teamcity.github.bridge.pullRequest.number` | string | branch name |
| `teamcity.github.bridge.pullRequest.title` | string | GitHub API |
| `teamcity.github.bridge.pullRequest.author` | string | GitHub API |
| `teamcity.github.bridge.pullRequest.sourceBranch` | string | GitHub API |
| `teamcity.github.bridge.pullRequest.targetBranch` | string | GitHub API |
| `teamcity.github.bridge.pullRequest.headSha` | string | GitHub API |

The variables are always defined for opted-in builds (empty
strings on non-PR branches), so DSL conditions never raise
"Unresolved parameter".

### Inbound surface

- `POST /app/teamcity-github-bridge/webhook` accepts signed
  GitHub App webhooks. HMAC-SHA256 over the raw body is mandatory
  and **fail-closed**: missing or invalid signature returns 401
  before the payload is parsed.
- `pull_request` events with action `ready_for_review` re-enqueue
  every matching build configuration via
  `ReadyForReviewListener`.
- `ping` events return `200 pong`. Other events return `204 No
  Content`.
- Replay protection: not implemented in 1.0; see
  [doc/roadmap.md](doc/roadmap.md).

### Outbound behaviour

- `DraftBuildQueueCleaner` removes draft-PR builds from the queue
  on `buildTypeAddedToQueue` so the queue stays clean.
  `DraftAwareBuildFilter` is kept as a safety net for cases where
  the cleaner cannot resolve the PR state in time.
- `DraftCheckRunReporter` posts a GitHub Check Run with
  `conclusion=skipped` for held draft builds so the PR shows the
  deliberate skip rather than "Expected - Waiting for status".
- `BuildStatusCheckRunPublisher` posts a Check Run on
  `buildStarted` (`status=in_progress`) and `buildFinished`
  (`status=completed`, `conclusion` mapped from TC `Status` +
  `isInterrupted`). `output.summary` carries the build's
  `statusDescriptor.text` so the agent's
  `##teamcity[buildStatus text='...']` survives the trip to
  GitHub.
- `PrPromotionTagger` adds the `draft` / `ready` tag to the
  promotion at enqueue time; the tag is visible everywhere TC
  shows build tags.
- `PrBuildEnricher` enriches the running build's number with the
  source branch name (e.g. `#87 feature/raycast-shadows`).
- `BranchEnrichmentPageExtension` re-styles the `draft` and
  `ready` tags as colored pills in the TC build lists
  (client-side CSS overlay; no API calls).

### Token acquisition

- Two-tier resolution: tries
  `ProjectConnectionCredentialsManager.requestConnectionCredentials`
  first (which triggers minting via the bundled github-app
  provider), then falls back to
  `OAuthTokensStorage.getProjectTokens` (cache-only) when the
  provider type is not registered with the credentials manager.
- Tokens are treated as opaque strings end-to-end; the plugin
  makes no assumption about format or length, so the new
  ~520-character stateless `ghs_*` token format works without
  changes.
- Warnings are rate-limited to once per minute per (project,
  connection) pair to avoid log floods on misconfigured servers.

### Configuration surface

- The HMAC webhook secret can be set from the admin page (with
  CSRF protection) or from
  `<TC_DATA_DIR>/config/teamcity-github-bridge.properties` (key
  `webhook.secret`), or as a legacy fallback from
  `<TC_DATA_DIR>/config/internal.properties` (key
  `teamcity.github.bridge.webhook.secret`).
- Per-buildType opt-in via three parameters (above).
- See [doc/configuration.md](doc/configuration.md) for the full
  knob list.

### Operational surface

- Native admin page under `Administration -> Server
  Administration -> GitHub Bridge`: plugin status, HMAC secret
  form, recent webhook deliveries (in-memory ring buffer of
  100), one-click **Run self-tests** button.
- Self-tests cover seven categories: webhook secret config,
  dedicated log file, GitHub API reachability, HMAC roundtrip,
  webhook self-delivery, token resolution per opted-in project,
  GitHub API auth via the resolved token.
- Dedicated log file is auto-attached at startup at
  `<TC_DATA_DIR>/logs/teamcity-github-bridge.log` with size-based
  rotation (10 MB per file, 10 files retained ~ 100 MB).
- `GET /app/teamcity-github-bridge/info` and `/info.md` return a
  live snapshot of the configuration ready to paste into the
  GitHub App settings.

### Compatibility

- TeamCity Server 2026.1 (build 222521) or newer.
- Java 21 (the SDK is compiled at this level).
- TeamCity bundled plugins `commit-status-publisher` and
  `pullRequests` can stay enabled alongside this plugin, but
  this plugin's coverage is broader; see
  [doc/configuration.md#check-run-publisher-coexistence-with-the-bundled-commitstatuspublisher](doc/configuration.md#check-run-publisher-coexistence-with-the-bundled-commitstatuspublisher)
  for the operating models.

[1.0.0]: ../../releases/tag/1.0.0
