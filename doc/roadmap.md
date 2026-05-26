# Roadmap

Forward-looking work items that did not make the 1.0 cut but are
considered well-understood. Each section captures the problem, the
known constraints, the proposed approach, and the level of effort.
Pick any one and ship it on its own branch.

The architectural baseline these items extend is documented in
[architecture.md](architecture.md). The shipped feature surface is
described in [README.md](../README.md) and detailed in
[configuration.md](configuration.md).

## Item 1 - Build feature for one-click opt-in

### Problem statement

Opt-in today requires setting three build parameters on every
participating build configuration (or on a shared template):

```
teamcity.github.bridge.ignoreDrafts
teamcity.github.bridge.repo
teamcity.github.bridge.connectionId
```

This works but has no UI affordance. The buildType editor shows
the parameters as generic key-value pairs, with no hint that the
plugin uses them.

### Proposed design

Provide a custom `BuildFeature` (subclass of
`jetbrains.buildServer.serverSide.BuildFeature`) titled "GitHub
Bridge integration" with a dedicated edit form that exposes the
three parameters as named fields plus a "connection" dropdown
populated from the project's `OAuthConnectionsManager`. Saving the
feature writes the same three parameters underneath, so the rest
of the plugin keeps working unchanged.

Benefits:
- Discoverable in the buildType editor.
- The connection dropdown removes the need to look up
  `PROJECT_EXT_<N>` / `CID_<hash>` by hand.
- A future "Disable bundled `commitStatusPublisher`" checkbox can
  live in the same form.

### Effort

Medium. Mostly UI work (JSP form + parameter mapping). The wiring
to the existing parameter conventions is mechanical.

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

## Item 3 - Replay protection on inbound webhooks

### Problem statement

`SignatureVerifier` validates HMAC-SHA256 over the body, which
prevents tampering but does not prevent **replay** of a captured
delivery. An attacker who recorded a webhook payload + signature
could re-deliver it.

### Proposed design

Track the `X-GitHub-Delivery` header (an opaque UUID GitHub
assigns to each delivery) in a bounded LRU. On every incoming
webhook:

1. Check the delivery ID against the seen set.
2. If new, process and remember.
3. If already seen, return `200 OK` with a `dropped-replay`
   marker (do not 4xx; GitHub interprets that as failure and
   retries).

Capacity around 1000 entries with 24-hour TTL matches GitHub's
own retry envelope.

### Effort

Small. ~50 lines plus a unit test.

## Item 4 - Disable the bundled `commitStatusPublisher` automatically

### Problem statement

When `BuildStatusCheckRunPublisher` is active on a buildType, the
TC bundled `commitStatusPublisher` still posts its hard-coded
`"TeamCity build finished"` description on commit statuses. The
result is a duplicate row per buildType on the GitHub PR UI.

Today the operator must disable the bundled publisher manually on
each opted-in build type.

### Proposed design

When the plugin's Build Feature (item 1) is enabled on a
buildType, automatically suppress the bundled
`commitStatusPublisher` feature for the same build type. Either by
removing the feature descriptor at runtime (fragile) or by
contributing a `BuildPromotionHook` that prevents the bundled
publisher from running for opted-in builds (cleaner).

### Constraints

The bundled `commitStatusPublisher` is part of TC's bundled plugin
set. Disabling it cleanly per-buildType is **not** a public DSL
setting today. The implementation will likely depend on
`BuildPromotionEx` and may break across TC releases.

### Effort

Medium to large. Risk-heavy because the integration with the
bundled publisher is intentionally not exposed.

## Item 5 - Mirror legacy `teamcity.pullRequest.*` variable names

### Problem statement

Consumers who used the bundled `pullRequests` build feature have
DSL references to `teamcity.pullRequest.title`, `.author`, etc.
Disabling that feature in favour of this plugin requires renaming
every DSL reference to `teamcity.github.bridge.pullRequest.*`.

### Proposed design

Add an opt-in property
`teamcity.github.bridge.publishLegacyAliases=true` (default
`false`) that also publishes the bundled feature's variable names
as aliases of the same values. Disabled by default to avoid
collision with the bundled feature when both are active; the
property is the operator's signed consent that the bundled
feature has been disabled.

### Effort

Small. ~20 lines in `PrParameterProvider` + a test + a doc note.

## Item 6 - `pull_request_review` event handling

### Problem statement

The plugin currently reacts only to `pull_request` (action
`ready_for_review`). Reviews (`approved`, `changes_requested`)
could drive useful behaviour, e.g. retrigger expensive end-to-end
suites only after approval, or surface review state as a build
tag.

### Proposed design

Extend `WebhookPayloadParser` and `PluginWebhookController` to
recognise `pull_request_review`. Add a new
`ApprovedReviewListener` that conditionally enqueues build types
tagged with a new opt-in parameter
`teamcity.github.bridge.runOnApproval=true`.

### Effort

Medium. ~150 lines plus webhook event payload tests.

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

The 84 unit tests cover pure logic. Integration with TC SDK
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

## Open SDK questions worth revisiting

These items are blocked on JetBrains shipping a SDK feature rather
than on our willingness to ship them. Re-check on each TC release.

| Question | What we want | Status as of TC 2026.1 |
|---|---|---|
| Public `BuildBranchInfoProvider` | Override the branch column display | Not in `server-openapi`; see Item 2 above. |
| Per-buildType disable of bundled features via DSL | Suppress `commitStatusPublisher` cleanly | Not in `server-openapi`; see Item 4. |
| `ConnectionCredentialsFactory` for GitHub App | High-level token acquisition that does not need our self-mint path | Not supported (`Unsupported Connection Provider type: GitHubApp`). **Worked around in v1.2.0** by the self-mint path (Item 9). When/if JetBrains adds it, the self-mint primary path can be dropped — the credentials-manager fallback would suffice again. |

## Where to record new ideas

Open a GitHub issue with the `enhancement` label. Once an
implementation plan is sketched in the issue, mirror it here so
this file remains the single source of truth for "what's next".
