# Changelog

All notable changes to this project are documented in this file.
The format follows [Keep a Changelog](https://keepachangelog.com/)
and the project adheres to [Semantic Versioning](https://semver.org/).

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

[1.3.0]: ../../releases/tag/v1.3.0

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

[1.2.0]: ../../releases/tag/v1.2.0

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

[1.0.0]: ../../releases/tag/v1.0.0
