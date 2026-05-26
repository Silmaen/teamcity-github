# Changelog

All notable changes to this project are documented in this file.
The format follows [Keep a Changelog](https://keepachangelog.com/)
and the project adheres to [Semantic Versioning](https://semver.org/).

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
