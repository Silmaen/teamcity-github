# Roadmap — known gaps to close

> **Audience**: an AI agent (or human) picking this up cold.
> This document is self-contained — you do not need prior conversation
> history. File paths, SDK signatures, and design constraints are
> spelled out below.
>
> The summary roadmap in
> [development.md#roadmap](development.md#roadmap) lists every planned
> item with one-line status; this file expands the items that need a
> deeper design discussion.

## Status of the integration audit (2026-05-25 → 2026-05-25)

A three-gap audit was performed when this plugin was first wired into
the Owl project (`/data/sources/personnel/stack_owl/Owl`). The first
two gaps shipped within the audit window; the third shipped partially.
A new fourth gap was discovered in the process and is the **primary
open item** below.

| Audit gap | Shipped? | Component |
|---|---|---|
| #A1 — Tag held-in-queue draft builds | ✅ shipped | `enrich/PrPromotionTagger` |
| #A2 — Branch display customisation | ✅ shipped | `web/BranchEnrichmentPageExtension` + `display/tcghBranchEnrichment.jsp` |
| #A3 — Enriched commit status publisher | ⚠️ partial | `report/BuildStatusCheckRunPublisher` — covers opted-in PR builds only |
| #A4 — Extend status publisher to cover main + opt-out PR builds | ❌ open | (this doc, section [Gap A4](#gap-a4)) |

## Snapshot of the plugin (read this before opening a feature)

| Component | Hook | Purpose |
|---|---|---|
| `DraftAwareBuildFilter` (`filter/`) | `StartBuildPrecondition.canStart` | Holds queued builds for draft PRs when `tcgh.ignoreDrafts=true`. |
| `ReadyForReviewListener` (`retrigger/`) | called from `PluginWebhookController` | On `pull_request.ready_for_review` webhook, re-enqueues every opted-in build type. |
| `PrPromotionTagger` (`enrich/`) | `BuildServerAdapter.buildTypeAddedToQueue` | Tags the `BuildPromotion` with `draft` or `ready` at enqueue time, so held builds also carry the marker. |
| `PrBuildEnricher` (`enrich/`) | `BuildServerAdapter.buildStarted` | Once a build *starts*, sets `buildNumber = "<n> <headRef>"`. Tagging was moved out to `PrPromotionTagger`. |
| `DraftCheckRunReporter` (`report/`) | `BuildServerAdapter.buildTypeAddedToQueue(SQueuedBuild)` | When a held draft build hits the queue, publishes a GitHub Check Run with `conclusion=skipped`. Dedup keyed on `(headSha, buildTypeExternalId)`. |
| `BuildStatusCheckRunPublisher` (`report/`) | `buildStarted` + `buildFinished` | Publishes Check Runs for the normal lifecycle of an **opted-in PR build** (`tcgh.ignoreDrafts=="true"` and ref starts with `pull/`). Carries the agent's `buildStatus text=...` into the `output.summary`. |
| `BranchEnrichmentPageExtension` (`web/`) | `PlaceId` injection of `tcghBranchEnrichment.jsp` | Renders a `[draft]`/`[ready]` pill next to PR branches in TC build lists. |
| `GitHubClient` (`api/`) | — | REST client: `getPr()`, `postCheckRun()` (handles `status` + nullable `conclusion`). Tokens are opaque strings. |
| `PrInfoCache` (`cache/`) | — | 60s TTL in-memory cache keyed on `(repo, prNumber)`. Falls back to last known value on fetch failure. |
| `TokenResolver` (`api/`) | — | Resolves a GitHub App installation token via `OAuthConnectionsManager` + `OAuthTokensStorage`. Connection ID format is `CID_<hash>`. |
| `RecentEventsLog` + `AdminConsolePage` (`web/`) | — | In-memory ring buffer of recent plugin events, exposed via the admin console JSP. |

Three buildType parameters drive opt-in (see `DraftAwareBuildFilter.PARAM_*`):

- `tcgh.ignoreDrafts` (`"true"` to enable suppression + Check Run publishing)
- `tcgh.github.repo` (e.g. `Silmaen/Owl`)
- `tcgh.github.connectionId` (e.g. `CID_392f0141078df64b20e1bb01ada5697f`)

What is **not yet** done — and why this document still exists:

- The bundled `commitStatusPublisher` is left in place by every
  consuming project (Owl included). It still posts the hardcoded
  `"TeamCity build finished"` description for **every** build, which
  causes **duplicate rows** on the GitHub PR UI for opted-in PR builds
  (both the bundled Commit Status and the plugin's Check Run appear).
- Removing the bundled publisher consumer-side is a footgun: see the
  detailed analysis below.

---

## Gap A4 — Extend BuildStatusCheckRunPublisher to cover main + opt-out PR builds  {#gap-a4}

### Problem statement

`BuildStatusCheckRunPublisher` short-circuits when the build's
parameter `tcgh.ignoreDrafts` is not `"true"` and when the branch ref
does not start with `pull/`:

```kotlin
// BuildStatusCheckRunPublisher.kt:96–102
private fun resolveContext(build: SBuild): PrBuildContext? {
    val branchName = build.branch?.name ?: return null
    if (!branchName.startsWith("pull/")) return null              // (1) skips main
    val buildType = build.buildType ?: return null
    if (buildType.parameters[DraftAwareBuildFilter.PARAM_IGNORE_DRAFTS] != "true") return null  // (2) skips opt-out
    ...
}
```

Concrete consequences for a consumer project (Owl is the worked
example below):

| Build context | Plugin Check Run | Bundled Commit Status | GitHub PR view |
|---|---|---|---|
| Build on `main` (post-merge) | ❌ doesn't fire (filter (1)) | ✅ fires | only the bundled commit status — fine in isolation |
| PR build, `tcgh.ignoreDrafts=true` (default opt-in) | ✅ fires | ✅ fires | **duplicate row** per buildType — the user-facing problem |
| PR build, `tcgh.ignoreDrafts=false` (draft-friendly subset: e.g. Linux x64 Clang, Sanitizer Address) | ❌ doesn't fire (filter (2)) | ✅ fires | only the bundled commit status |
| BuildType with no `tcgh.*` params at all (e.g. CodeStyle in Owl) | ❌ doesn't fire | ✅ fires | only the bundled commit status |

The duplicate row in the second case is the immediate UX issue, but
the deeper problem is that **a consumer cannot retire the bundled
publisher** without losing coverage on the other three rows. The
plugin doc currently lists this as an explicit choice:

```
| Keep both publishers (informational fallback) | Update branch protection ... |
| Single source of truth | Disable the bundled publisher per buildType ... |
```

Neither is satisfying:

- "Keep both" leaves the duplicate row visible. Branch protection
  configuration is a manual GitHub-side fix that does nothing for the
  visual noise.
- "Disable bundled per buildType" cannot be done from Kotlin DSL — the
  bundled `commitStatusPublisher` is template-level and `disableSettings`
  by id only works if the consumer knows the id (`BUILD_EXT_7` in
  Owl's case). Even then, you'd be disabling it on every PR opt-in
  buildType *and* losing main coverage, since the plugin doesn't
  publish for main today.

### Root cause

`BuildStatusCheckRunPublisher` was scoped to "the same population
DraftAwareBuildFilter manages" because the opt-in token was reused.
That decision is the bottleneck. A wider publisher could replace the
bundled one entirely.

### Proposed design

Decouple the **draft-suppression opt-in** (`tcgh.ignoreDrafts`) from
the **publisher opt-in**. Two options:

**Option 1 — Implicit: just remove the two guards.**

Make `BuildStatusCheckRunPublisher` fire whenever the buildType
carries `tcgh.github.repo` + `tcgh.github.connectionId` (regardless of
`ignoreDrafts` value, and regardless of ref). Effects:

- All builds with the repo+connection params get a Check Run.
  Draft-friendly buildTypes (e.g. Owl's Linux x64 Clang on PR) now
  also get one.
- Main branch builds get a Check Run. But Check Runs are scoped to a
  commit, so this means every main commit gets a per-buildType row in
  the **GitHub commit's checks** view (visible from the commit page,
  not the PR page). That's actually what users expect from CI on main.

Pros: simplest implementation, zero new parameters.
Cons: changes existing semantics of `tcgh.ignoreDrafts` slightly —
some consumers may rely on it to gate publishing too. Should be
documented as a breaking change in CHANGELOG.

**Option 2 — Explicit: add a fourth parameter.**

Introduce `tcgh.checkRuns.publish` (default `"true"` when the other
two repo+connection params are set). Treat it as a kill switch.
`BuildStatusCheckRunPublisher` checks repo + connection + this new
param; `DraftAwareBuildFilter` keeps using `ignoreDrafts`. The two
opt-ins become orthogonal.

Pros: backward compatible, lets consumers opt out cleanly.
Cons: more parameters to set; one more thing to document.

**Recommendation**: ship Option 1 first (simpler, the breaking change
is contained — the only behavioural difference is "more Check Runs are
published than before, which is what most consumers want"). Document
prominently in CHANGELOG that consumers depending on the old gating
should pre-emptively unset `tcgh.github.repo` on buildTypes that
should not publish.

### Code changes (Option 1 sketch)

```kotlin
// BuildStatusCheckRunPublisher.kt
private fun resolveContext(build: SBuild): PrBuildContext? {
    val buildType = build.buildType ?: return null
    val repoSlug = buildType.parameters[DraftAwareBuildFilter.PARAM_REPO_SLUG] ?: return null
    val connectionId = buildType.parameters[DraftAwareBuildFilter.PARAM_CONNECTION_ID] ?: return null

    val headSha = build.revisions.firstOrNull()?.revision?.takeIf { it.isNotBlank() } ?: return null
    val token = tokenResolver.resolveAccessToken(buildType.project, connectionId) ?: return null

    return PrBuildContext(
        repo = RepoCoords.parse(repoSlug),
        buildType = buildType,
        headSha = headSha,
        accessToken = token,
    )
}
```

That's literally it — drop lines 96 (`pull/` filter) and 99
(`ignoreDrafts` filter).

`DraftCheckRunReporter` keeps its `ignoreDrafts == "true"` guard (it
makes sense there — that reporter is *about* held drafts). Only the
status publisher widens.

### Tests to add / update

- New test case in `BuildStatusCheckRunPublisherTest`: pure-helper
  variant of `resolveContext` should return a non-null context for
  `branch=main` and for `ignoreDrafts=false`, given the two other
  params are set.
- Document the new semantics in `configuration.md` (the
  "Check Run publisher coexistence" table needs an extra row).
- Verify on a real TC instance: a build on main produces a Check Run;
  Owl's `Linux x64 Clang` (opt-out) on PR also produces a Check Run.

### Then: retire the bundled publisher consumer-side

Once Option 1 lands, consumers (Owl, others) can safely remove the
bundled `commitStatusPublisher` from their template's `features { … }`
block. Trade-off they should know:

- ✅ One Check Run per buildType per build → clean GitHub UI.
- ⚠️ Required Checks on GitHub branch protection must be reconfigured
  to use the Check Run names (`TeamCity / <buildType.fullName>`)
  rather than the legacy commit status contexts.

Owl's wiring lives in:

```
/data/sources/personnel/stack_owl/Owl/.teamcity/_Self/buildTypes/GlobalBuild.kt
/data/sources/personnel/stack_owl/Owl/.teamcity/_Self/buildTypes/CodeStylingCheck.kt
```

Look for `commitStatusPublisher { id = "BUILD_EXT_7" }` and
`id = "BUILD_EXT_3"`. After this gap closes, those blocks become
candidates for deletion (or `disableSettings(...)` on inheritors).

### Risks / edge cases

- **`SBuild.revisions` empty for personal builds**: already handled
  via `return null` on blank SHA. Personal builds simply skip
  publishing — fine.
- **Check Run rate limits**: GitHub's REST limit is 5000 req/hour for
  Apps. The cache + dedup already in place handle the common case.
  Worth verifying with a real run on a busy TC.
- **GitHub Enterprise**: `tcgh.github.api.base` already overridable
  via internal property. Check Runs API path is identical (`/repos/.../check-runs`).
- **`details_url`**: the request already supports a `detailsUrl` field
  (added in the v0.5.0 plugin upgrade). Pointing it at TC's build page
  would let GitHub deep-link back. Out of scope for this gap but
  trivial to layer on top — pass `${build.buildType.url}` / similar.

### Effort

Small. The actual code change is ~10 lines. Most of the work is
testing + documentation + verifying that nothing in consumer projects
breaks.

Files touched (expected):

```
src/main/kotlin/.../report/BuildStatusCheckRunPublisher.kt   (drop two guards)
src/test/kotlin/.../report/BuildStatusCheckRunPublisherTest.kt (add cases)
doc/configuration.md                                          (update coexistence table)
CHANGELOG.md                                                  (note the widening of the publisher)
```

---

## Other backlog items

These have not had a deep audit yet but are still tracked in
[development.md#roadmap](development.md#roadmap). Listed here only for
context; pick one of them up only after #A4 ships, since #A4 affects
how the others should be designed.

| # in development.md | Item | Why it can wait |
|---|---|---|
| #2 | Custom `BuildFeature` to surface the parameters in the UI | Cosmetic; doesn't fix any current functional gap. |
| #5 | Webhook delivery replay protection via `X-GitHub-Delivery` | Real concern only at higher webhook traffic. |
| #6 | Wire `pull_request_review` events | Useful for surfacing review state but not blocking any current consumer. |
| #8 | CI workflow building + releasing on tag | Pure dev-experience win. |

---

## Cross-cutting concerns

### Validation against a real TeamCity instance

`#A4` cannot be fully validated by unit tests. After it's coded,
install the rebuilt zip on a staging TC instance with the Owl
project's parameters wired up and run the manual smoke sequence:

1. Open a draft PR with a one-line change.
   - Verify TC queue UI: held builds carry `draft` tag (already
     shipped via `PrPromotionTagger`).
   - Verify GitHub PR view: held builds appear as ⏭️ skipped Check Runs.
2. Mark the PR ready for review.
   - Verify ready-for-review retrigger fires.
   - Verify both opt-in and opt-out buildTypes publish Check Runs.
   - Verify NO duplicate row from the bundled publisher (assuming the
     consumer has removed it after #A4).
3. Push to `main` directly.
   - Verify Check Run rows appear on the commit's checks panel.
4. Push a new commit to the still-open PR.
   - Verify enrichment re-applies to the new revision.

### Local development workflow recap

```bash
cd /data/sources/Sources/IT/teamcity-github
./dev test            # JUnit5 tests (currently 63, will grow)
./dev package         # produces target/teamcity-github-bridge-*.zip
./dev shell           # interactive bash in the maven container
```

SDK introspection from the dev shell (used for any new SDK-touching
gap):

```bash
jar tf '/workspace/.cache/m2/org/jetbrains/teamcity/server-openapi/2026.1/server-openapi-2026.1.jar' \
    | grep -iE 'SomeClassName'

javap -cp '/workspace/.cache/m2/org/jetbrains/teamcity/server-openapi/2026.1/server-openapi-2026.1.jar' \
    -p jetbrains.buildServer.serverSide.SomeClass
```

### Convention reminders (taken from development.md)

- Spring bean registration: add `<bean class="…"/>` in
  `src/main/resources/META-INF/build-server-plugin-tcgh-bridge.xml`.
- Logger: `Logger.getInstance(MyClass::class.java.name)` in a
  companion object; tests call `LoggerBootstrap.install()` in `init`.
- No mocking framework; stub interfaces or extract pure helpers (see
  `PrPromotionTagger.computePlan` / `DraftCheckRunReporter.buildRequest`
  / `BuildStatusCheckRunPublisher.mapBuildOutcome` for the pattern).
- Comments only when the *why* is non-obvious; don't restate the code.
- One feature per PR; update the relevant `doc/*.md` page in the same
  PR.

### Where Owl touches the plugin

The Owl repo references the three `tcgh.*` parameters in:

```
/data/sources/personnel/stack_owl/Owl/.teamcity/_Self/buildTypes/GlobalBuild.kt
/data/sources/personnel/stack_owl/Owl/.teamcity/Build/Build.kt
```

If you rename or remove a parameter in the plugin, search-and-replace
both files and bump the Owl team. The connection ID currently
hardcoded in Owl's DSL is `CID_392f0141078df64b20e1bb01ada5697f`.
