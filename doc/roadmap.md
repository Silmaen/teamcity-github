# Roadmap

Forward-looking work items that did not make the 1.0 cut but are
considered well-understood. Each section captures the problem, the
known constraints, the proposed approach, and the level of effort.
Pick any one and ship it on its own branch.

The architectural baseline these items extend is documented in
[architecture.md](architecture.md). The shipped feature surface is
described in [README.md](../README.md) and detailed in
[configuration.md](configuration.md).

## Shipped since 1.0

- v1.9.0 — builds launched on a plain branch ref are attached to their
  pull request: the PR is resolved from the built commit
  (`GET /commits/{sha}/pulls`, open PRs whose head is that commit only),
  so such a build gets the `teamcity.github.bridge.pullRequest.*`
  parameters, the `draft`/`ready` tag and the sticky summary comment.
  New `branchPrLookup.enabled` feature flag (default on). Gating is
  untouched — a branch build still takes the gate's branch path.
- v1.8.0 — PR-metadata build gate (per-build-configuration
  `requirePhrase` / `skipPhrase` / `labelFilter` over the PR title,
  body and labels; SOFT, manual bypasses, "Skipped: PR metadata out of
  scope" Check Run). Comment triggers moved to
  `pull_request_review_comment` (inline diff comments) since GitHub only
  exposes `issue_comment` with the Issues permission, which the plugin
  does not request.
- v1.2.0 — self-mint installation tokens (Item 9 below) + GitHub
  Enterprise support across every REST endpoint.
- v1.3.0 — full Check Run lifecycle (queued / in_progress /
  interrupted / finished / queue-cancelled), `details_url` on every
  Check Run pointing at the TC build, tag-all-opted-in-PR-builds
  (no longer gated on `ignoreDrafts=true`), and manual user triggers
  bypass the draft-PR suppression flow.
- v1.4.0 — react to `pull_request.opened` and
  `pull_request.synchronize` in addition to `ready_for_review`,
  unblocking the "drop VCS triggers on opt-in BuildTypes" pattern.
  `ReadyForReviewListener` is now `PullRequestEventListener`. The
  draft flag is read from the webhook payload (no token cost), and
  `PrInfoCache` is invalidated before enqueue to keep
  `DraftBuildQueueCleaner` from dropping fresh builds against
  stale cache entries.
- v1.5.0 — **Breaking.** Operator-feedback overhaul. Opt-in is
  now a Build Feature on each BT plus four project-level
  parameters (`branchTrigger.enabled` / `branches`,
  `prTrigger.enabled` / `branches`). Per-BT trigger flags
  (`triggerOnBranch` / `triggerOnPrReady` / `triggerOnPrDraft`)
  with HARD semantics — manual triggers cannot bypass.
  Per-BT branch list overrides. Centralized gating in
  `BridgeGate.decide` shared by listener / filter / cleaner /
  publisher. Skipped Check Runs on GitHub for the two PR-context
  suppression reasons. Listener runs as system user via
  `SecurityContextEx.runAsSystemUnchecked`; smart-skip on
  existing builds at `(pull/N, head SHA)`; case-insensitive
  repo slug compare. See CHANGELOG for the migration matrix.
- v1.7.0 — operator surface + trust-boundary completion. Shipped
  the bulk of the items previously listed below:
  webhook **replay protection** (`DeliveryReplayGuard`, Item 3);
  legacy `teamcity.pullRequest.*` **aliases** (opt-in
  `legacyAliases.enabled`, Item 5); **`pull_request_review`
  run-on-approval** (Item 6); **path filtering**, repo
  **allowlist** + **dry-run** mode; **retry / rate-limit handling**
  in `GitHubClient`; **`/health` + metrics** and the recent-events
  log; reaction to **closed / merged** PRs; **re-run from GitHub**
  (`check_run.rerequested`); the sticky **PR summary comment**
  (`prComment.enabled`, needs pull-requests write); **comment-
  triggered builds** (`issue_comment`, author_association
  allowlisted); and the **external authenticated API**
  (`/api/status|events|metrics|trigger`, bearer token). All new
  trigger paths run as the TC system user. Build-failure reasons are
  now surfaced in the Check Run `output.text` (partial annotations —
  see Item 10). See [security.md](security.md) for the trust model.
- v1.6.0 — correctness fixes. The opt-in feature is now honoured
  when inherited from a BuildType template (`BridgeFeatureReader`
  reads `resolvedSettings`, not own-features-only). PR builds that
  leave the queue without running — chiefly "failed to start" on a
  failed snapshot dependency — now reach a terminal `Build failed`
  Check Run instead of staying stuck at "Queued"; duplicate
  build-chain promotions torn down without a record are ignored so
  they cannot overwrite the real result.

## Item 1 - Build feature for one-click opt-in — **SHIPPED in 1.5.0**

Shipped as `GitHubBridgeBuildFeature` (Spring bean) backed by the
`bridgeFeatureEdit.jsp` edit form. The feature carries five
fields: `triggerOnBranch` / `triggerOnPrReady` / `triggerOnPrDraft`
(HARD trigger gates) + `branchTriggerBranchesOverride` /
`prTriggerBranchesOverride` (BT-level branch list overrides
that REPLACE the project defaults when set). Mandatory project
config (`repo`, `connectionId`, the two `xxxTrigger.enabled`
toggles, the two `xxxTrigger.branches` lists) lives at the
project level as standard TC parameters. The OAuth connection
dropdown sketched in the original design is deferred (the
project param is a plain text input; surfacing live connections
via `OAuthConnectionsManager` is the natural follow-up if
operators ask for it).

## Item 2 - Branch column customisation (server-side)

### Problem statement

The TeamCity 2026.1 SDK does not publish a public extension point
to override the value shown in the "Branch" column of build lists.
The plugin currently renders the `draft` / `ready` tags as styled
pills via `BranchEnrichmentPageExtension`, which is a client-side
CSS overlay.

A server-side replacement would let us display the source branch
name (e.g. `feature/raycast-shadows`) inline with the PR ref
without depending on the rendered DOM staying stable.

### Constraints (verified via SDK introspection)

- `BuildBranchInfoProvider` does not exist on the public SDK in
  2026.1.
- `BranchDisplayNameProvider` does not exist either.
- `Branch.getDisplayName()` is read-only with no override hook.
- `BuildPromotion.setDesiredBranchName()` rewrites the actual ref,
  not the display.

### Proposed design

Two options, in priority order:

1. **Wait for JetBrains.** Track the TeamCity issue tracker for a
   public `BuildBranchInfoProvider`-like API and adopt it when
   available.
2. **Browser-side enrichment.** Extend
   `BranchEnrichmentPageExtension` to fetch a compact JSON payload
   (e.g. `/app/teamcity-github-bridge/branches`) and rewrite the
   branch column in the DOM. Risk: brittle to TC UI changes.

### Effort

Medium to large. Option 1 is no work but unbounded wait; option 2
needs new server endpoint + client JS + careful retry / debounce.

## Item 3 - Replay protection on inbound webhooks — **SHIPPED in 1.7.0**

Shipped as `DeliveryReplayGuard`. The `X-GitHub-Delivery` UUID is
tracked in a bounded LRU (`DEFAULT_MAX_ENTRIES` = 2000) with a
24-hour TTL. The check runs **after** signature verification: a
delivery id already seen within the TTL is acknowledged `200 OK`
("duplicate delivery ignored"), recorded as `SKIPPED`, counted under
`webhooks.replayed`, and **not** re-processed; a 4xx is deliberately
avoided so GitHub does not treat it as a failed delivery and retry.
Enabled by default (`webhook.replay.enabled`), toggleable from the
admin page. See [security.md](security.md) *Inbound: replay
protection*.

## Item 4 - Warn when the bundled `commitStatusPublisher` is also active

### Problem statement

When `BuildStatusCheckRunPublisher` is active on a buildType, the
TC bundled `commitStatusPublisher` still posts its hard-coded
`"TeamCity build finished"` description on commit statuses. The
result is a duplicate row per buildType on the GitHub PR UI.

### Decision (2026-07-28): warn, never act

Auto-suppressing the bundled feature was the original idea and is
**rejected**:

- Which system reports to GitHub is a *configuration decision* that
  belongs to the operator; silently disabling another plugin's output
  is surprising behaviour.
- Refusing to publish would be worse — it removes reporting from the
  very builds the operator wants to observe.
- The mechanics are risky anyway (see *Constraints* below).

So the plugin's job is to make the misconfiguration **impossible to
miss**, and stop there. Correcting it is the operator's call, and the
requirement is documented for them in
[configuration.md](configuration.md#choosing-the-right-setup),
[quickstart.md](quickstart.md) step 4 and
[troubleshooting.md](troubleshooting.md#symptom-pr-shows-two-teamcity-entries-commit-status--check-run).

### Proposed design

Detect, per opted-in buildType, whether the resolved feature set also
contains `commitStatusPublisher` (same `resolvedSettings` read that
`BridgeFeatureReader` already performs, so template-inherited
publishers are caught too), then:

1. a **`WARN` log line** naming the buildType, emitted once per
   buildType per server start (not per build — it would flood);
2. a **self-test row** on the admin page: *"N opted-in build
   configurations also carry the bundled Commit status publisher"*,
   listing them, with `WARN` status;
3. optionally a counter for the metrics endpoint.

No behaviour change: the bridge keeps publishing its Check Runs.

### Constraints

The bundled `commitStatusPublisher` is part of TC's bundled plugin
set; disabling it cleanly per-buildType is **not** a public DSL
setting, which is a second reason not to try. *Reading* the feature
set, by contrast, is plain public API.

### Effort

Small — a read, a log line and a self-test row.

## Item 5 - Mirror legacy `teamcity.pullRequest.*` variable names — **SHIPPED in 1.7.0**

Shipped in `PrParameterProvider`. When the opt-in flag
`legacyAliases.enabled` (set from the admin page) is on, the
provider also publishes the bundled feature's `teamcity.pullRequest.*`
names (`number`, `title`, `sourceBranch`, `targetBranch`) as aliases
of the same values. Off by default to avoid colliding with the
bundled feature when both are active — enabling it is the operator's
signal that the bundled feature has been disabled.

## Item 6 - `pull_request_review` event handling — **SHIPPED in 1.7.0**

Shipped. `PluginWebhookController` now recognises
`pull_request_review`; `WebhookPayloadParser.parseReviewApproved`
extracts the approval, and `PullRequestEventListener.handleReviewApproved`
(running as the system user) enqueues the matching opted-in build
types on approval, honouring the repo allowlist and skipping draft
PRs. Run-on-approval is the shipped form of the original idea.

## Item 7 - Release pipeline

### Problem statement

The plugin builds via `./dev package` but releases are produced
by hand: bump version, package, attach to a GitHub Release.

### Proposed design

A GitHub Actions workflow that triggers on `v*` tags:

1. Run `./dev test` and `./dev package`.
2. Verify the produced zip name matches the tag.
3. Create a GitHub Release with auto-generated release notes
   sourced from [CHANGELOG.md](../CHANGELOG.md).
4. Attach the zip as a release asset.

The workflow runs on `ubuntu-latest` with either a
Docker-in-Docker setup or a direct install of Maven 3.9 + JDK 21
(the latter is simpler in GitHub Actions).

### Effort

Small. One workflow file.

## Item 8 - End-to-end test fixture against a real TeamCity

### Problem statement

The 180+ unit tests cover pure logic. Integration with TC SDK
classes (`BuildServerAdapter`, `OAuthTokensStorage`,
`BuildPromotion`, etc.) is exercised only when the plugin is
installed on a real TC server.

### Proposed design

Use the
[`org.jetbrains.teamcity:tests-support`](https://search.maven.org/artifact/org.jetbrains.teamcity/tests-support)
artefact (in the TeamCity Maven repo) to spin up an in-memory TC
server in tests. Validate that:

- Spring DI wires successfully.
- The webhook endpoint registers anonymously.
- `removeFromQueue` cleanly removes a draft build promotion.

### Constraints

`tests-support` pulls in a substantial chunk of TC's server jar
graph and the in-memory server is slow to start (~30 s per test
class). Run as a Maven `verify`-phase suite, not on every
`./dev test`.

### Effort

Large. The harness is well documented but setting it up the first
time takes time.

## Item 9 - Self-mint installation tokens (TC 2026.1 unblock) — **shipped in v1.2.0**

### What shipped

A third token-acquisition path inside `TokenResolver` that mints
installation tokens directly from the connection's stored App ID +
private key. JWT signing via `auth0/java-jwt`, two REST calls to
GitHub (`/app/installations`,
`/app/installations/{id}/access_tokens`), local cache keyed on
installation ID with a 10 minute safety margin under the 60 minute
GitHub-side lifetime.

Resolution order is now:

1. **`AppTokenMinter.mint(...)` — primary, new in v1.2.0.** Works on
   a vanilla TC 2026.1 sandbox; no prior "Test connection" click
   needed.
2. `ProjectConnectionCredentialsManager.requestConnectionCredentials`
   (kept for forward-compatibility with a future TC fix).

The `OAuthTokensStorage.getProjectTokens` cache-only path that
older versions used as a fallback has been dropped: TC's "refresh
if necessary" flag does not refresh GitHub App tokens reliably on
2026.1, so the cache ended up handing out 401-rejected stale
tokens.

### Files added

- `src/main/kotlin/.../api/AppTokenMinter.kt`
- `src/main/kotlin/.../api/AppTokenCache.kt`
- `src/test/kotlin/.../api/AppTokenMinterTest.kt` (10 tests)
- `src/test/kotlin/.../api/AppTokenCacheTest.kt` (7 tests)

### Notes on the shipped implementation

The PEM parser handles both PKCS#1 (`-----BEGIN RSA PRIVATE KEY-----`)
and PKCS#8 (`-----BEGIN PRIVATE KEY-----`) without pulling
BouncyCastle: a tiny in-process ASN.1 wrapper converts PKCS#1 to
PKCS#8 so Java's stock `KeyFactory` can load it. Literal `\n`
escape sequences (when the key is pasted into a single-line field)
are normalised to real newlines before parsing.

## Item 10 - Check Run annotations / richer failure detail — **PARTIAL**

### What shipped (1.7.0)

`BuildStatusCheckRunPublisher` now populates the Check Run
`output.text` (the GitHub-rendered Markdown detail body, max 65535
chars) with the build's **failure reasons** via `failureDetails(build)`,
so a failed PR build surfaces *why* it failed in the PR's Checks tab
rather than only a red status.

### Still future work

Line-level **annotations** (the GitHub `output.annotations` array
that pins messages to specific files/lines, e.g. from compiler or
linter output) are not yet emitted. Mapping TeamCity build problems
and test failures to file/line annotations is the remaining work.

### Effort

Medium. Parsing build problems into annotation coordinates is the
bulk of it.

## Open SDK questions worth revisiting

These items are blocked on JetBrains shipping a SDK feature rather
than on our willingness to ship them. Re-check on each TC release.

| Question | What we want | Status as of TC 2026.1 |
|---|---|---|
| Public `BuildBranchInfoProvider` | Override the branch column display | Not in `server-openapi`; see Item 2 above. |
| Per-buildType disable of bundled features via DSL | Suppress `commitStatusPublisher` cleanly | Not in `server-openapi` — and **no longer wanted**: Item 4 decided to warn rather than act. Keep the row only as a record of the SDK state. |
| `ConnectionCredentialsFactory` for GitHub App | High-level token acquisition that does not need our self-mint path | Not supported (`Unsupported Connection Provider type: GitHubApp`). **Worked around in v1.2.0** by the self-mint path (Item 9). When/if JetBrains adds it, the self-mint primary path can be dropped — the credentials-manager fallback would suffice again. |

## Where to record new ideas

Open a GitHub issue with the `enhancement` label. Once an
implementation plan is sketched in the issue, mirror it here so
this file remains the single source of truth for "what's next".
