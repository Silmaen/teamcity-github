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
Two further gaps were discovered in the process — #A4 has since
shipped, #A5 is the **primary open item** below.

| Audit gap | Shipped? | Component |
|---|---|---|
| #A1 — Tag held-in-queue draft builds | ✅ shipped | `enrich/PrPromotionTagger` |
| #A2 — Branch display customisation | ✅ shipped | `web/BranchEnrichmentPageExtension` + `display/bridgeBranchEnrichment.jsp` |
| #A3 — Enriched commit status publisher | ⚠️ partial | `report/BuildStatusCheckRunPublisher` — covers opted-in PR builds only |
| #A4 — Extend status publisher to cover main + opt-out PR builds | ✅ shipped in v0.7.0 | `report/BuildStatusCheckRunPublisher.isOptedIn` (Option 1) |
| #A5 — Allow manual `Run` / `Re-run` to bypass the draft hold | ❌ open | `filter/DraftAwareBuildFilter` (see [Gap A5](#gap-a5)) |

## Snapshot of the plugin (read this before opening a feature)

| Component | Hook | Purpose |
|---|---|---|
| `DraftAwareBuildFilter` (`filter/`) | `StartBuildPrecondition.canStart` | Holds queued builds for draft PRs when `teamcity.github.bridge.ignoreDrafts=true`. |
| `ReadyForReviewListener` (`retrigger/`) | called from `PluginWebhookController` | On `pull_request.ready_for_review` webhook, re-enqueues every opted-in build type. |
| `PrPromotionTagger` (`enrich/`) | `BuildServerAdapter.buildTypeAddedToQueue` | Tags the `BuildPromotion` with `draft` or `ready` at enqueue time, so held builds also carry the marker. |
| `PrBuildEnricher` (`enrich/`) | `BuildServerAdapter.buildStarted` | Once a build *starts*, sets `buildNumber = "<n> <headRef>"`. Tagging was moved out to `PrPromotionTagger`. |
| `DraftCheckRunReporter` (`report/`) | `BuildServerAdapter.buildTypeAddedToQueue(SQueuedBuild)` | When a held draft build hits the queue, publishes a GitHub Check Run with `conclusion=skipped`. Dedup keyed on `(headSha, buildTypeExternalId)`. |
| `BuildStatusCheckRunPublisher` (`report/`) | `buildStarted` + `buildFinished` | Publishes Check Runs for the normal lifecycle of an **opted-in PR build** (`teamcity.github.bridge.ignoreDrafts=="true"` and ref starts with `pull/`). Carries the agent's `buildStatus text=...` into the `output.summary`. |
| `BranchEnrichmentPageExtension` (`web/`) | `PlaceId` injection of `bridgeBranchEnrichment.jsp` | Renders a `[draft]`/`[ready]` pill next to PR branches in TC build lists. |
| `GitHubClient` (`api/`) | — | REST client: `getPr()`, `postCheckRun()` (handles `status` + nullable `conclusion`). Tokens are opaque strings. |
| `PrInfoCache` (`cache/`) | — | 60s TTL in-memory cache keyed on `(repo, prNumber)`. Falls back to last known value on fetch failure. |
| `TokenResolver` (`api/`) | — | Resolves a GitHub App installation token via `OAuthConnectionsManager` + `OAuthTokensStorage`. Connection ID format is `CID_<hash>`. |
| `RecentEventsLog` + `AdminConsolePage` (`web/`) | — | In-memory ring buffer of recent plugin events, exposed via the admin console JSP. |

Three buildType parameters drive opt-in (see `DraftAwareBuildFilter.PARAM_*`):

- `teamcity.github.bridge.ignoreDrafts` (`"true"` to enable suppression + Check Run publishing)
- `teamcity.github.bridge.repo` (e.g. `Silmaen/Owl`)
- `teamcity.github.bridge.connectionId` (e.g. `CID_392f0141078df64b20e1bb01ada5697f`)

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

> **Status: ✅ shipped in v0.7.0** (Option 1 below).
>
> Both guards were removed from `BuildStatusCheckRunPublisher.resolveContext`:
> the `pull/` branch check and the `teamcity.github.bridge.ignoreDrafts == "true"`
> check. The single opt-in is now `teamcity.github.bridge.repo` +
> `teamcity.github.bridge.connectionId`, exposed as the pure helper
> `BuildStatusCheckRunPublisher.isOptedIn(parameters)` and
> covered by `BuildStatusCheckRunPublisherTest`.
>
> Consumer projects can now disable the bundled
> `commitStatusPublisher` on every opted-in buildType without
> losing GitHub PR coverage on main, opt-out PR builds, or
> `teamcity.github.bridge.ignoreDrafts=false` configurations. See
> [doc/configuration.md](configuration.md#check-run-publisher-coverage)
> for the new operating model.


### Problem statement

`BuildStatusCheckRunPublisher` short-circuits when the build's
parameter `teamcity.github.bridge.ignoreDrafts` is not `"true"` and when the branch ref
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
| PR build, `teamcity.github.bridge.ignoreDrafts=true` (default opt-in) | ✅ fires | ✅ fires | **duplicate row** per buildType — the user-facing problem |
| PR build, `teamcity.github.bridge.ignoreDrafts=false` (draft-friendly subset: e.g. Linux x64 Clang, Sanitizer Address) | ❌ doesn't fire (filter (2)) | ✅ fires | only the bundled commit status |
| BuildType with no `teamcity.github.bridge.*` params at all (e.g. CodeStyle in Owl) | ❌ doesn't fire | ✅ fires | only the bundled commit status |

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

Decouple the **draft-suppression opt-in** (`teamcity.github.bridge.ignoreDrafts`) from
the **publisher opt-in**. Two options:

**Option 1 — Implicit: just remove the two guards.**

Make `BuildStatusCheckRunPublisher` fire whenever the buildType
carries `teamcity.github.bridge.repo` + `teamcity.github.bridge.connectionId` (regardless of
`ignoreDrafts` value, and regardless of ref). Effects:

- All builds with the repo+connection params get a Check Run.
  Draft-friendly buildTypes (e.g. Owl's Linux x64 Clang on PR) now
  also get one.
- Main branch builds get a Check Run. But Check Runs are scoped to a
  commit, so this means every main commit gets a per-buildType row in
  the **GitHub commit's checks** view (visible from the commit page,
  not the PR page). That's actually what users expect from CI on main.

Pros: simplest implementation, zero new parameters.
Cons: changes existing semantics of `teamcity.github.bridge.ignoreDrafts` slightly —
some consumers may rely on it to gate publishing too. Should be
documented as a breaking change in CHANGELOG.

**Option 2 — Explicit: add a fourth parameter.**

Introduce `teamcity.github.bridge.checkRuns.publish` (default `"true"` when the other
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
should pre-emptively unset `teamcity.github.bridge.repo` on buildTypes that
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
- **GitHub Enterprise**: `teamcity.github.bridge.api.base` already overridable
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

## Gap A5 — Let manual `Run` / `Re-run` bypass the draft hold  {#gap-a5}

### Problem statement

`DraftAwareBuildFilter.canStart` (`filter/DraftAwareBuildFilter.kt`) treats
every queued build identically regardless of who put it in the queue.
That is the right default for VCS-triggered builds — those should be
held while the PR is draft, that's the whole point. But it has two
sharp edges:

1. **A human clicking "Run" in the TC UI** on an opt-in buildType for a
   draft PR sees their click silently swallowed: the build enters the
   queue and stays there with the wait reason
   `"PR #N is draft and teamcity.github.bridge.ignoreDrafts is enabled"`.
   Most users will not look at the wait reason and will conclude that
   "Run" is broken. Equivalent issue for the "Re-run" button on a
   failed build.
2. **The "Custom Build…" dialog override does not work.** Setting
   `teamcity.github.bridge.ignoreDrafts = "false"` for one specific run
   is the natural workaround a power user would try. The filter ignores
   it because it reads `buildType.parameters[PARAM_IGNORE_DRAFTS]` —
   the configured parameters on the buildType — not the merged
   parameters on the promotion (which would include the custom override).

Both problems compound: there is currently **no way** to force a single
draft-targeted run from the UI without temporarily editing the buildType
configuration. Users hit this when they want to spot-check that a slow
opt-in buildType (e.g. Linux x64 GCC) still compiles their draft branch
before they mark the PR ready.

### Current behaviour — exactly what fires

For the offending case (user clicks "Run" on opt-in buildType, PR draft):

1. New `BuildPromotion` created with the user as `TriggeredBy`.
2. Enters the queue → `buildTypeAddedToQueue` fires → tags applied by
   `PrPromotionTagger`, ⏭️ skipped Check Run published by
   `DraftCheckRunReporter`. **Both behave correctly — the build IS
   a draft build, so a skipped check is honest.**
3. `DraftAwareBuildFilter.canStart` polled repeatedly → returns
   `SimpleWaitReason(...)`. Build never starts.
4. User waits indefinitely. Their click had no effect.

### SDK signatures (verified via `javap` on `server-openapi-2026.1.jar`)

```
public interface jetbrains.buildServer.serverSide.TriggeredBy {
  public abstract boolean isTriggeredByUser();
  public abstract jetbrains.buildServer.users.SUser getUser();
  public abstract java.lang.String getRawTriggeredBy();      // matches the
  // requestor string we pass to BuildType.addToQueue(...) in
  // ReadyForReviewListener — useful to distinguish a plugin-issued
  // retrigger from a real user click.
  public abstract java.util.Map<java.lang.String, java.lang.String> getParameters();
  public abstract boolean isTriggeredBySnapshotDependency();
}

public interface jetbrains.buildServer.serverSide.BuildPromotion {
  public abstract java.util.Map<java.lang.String, java.lang.String> getCustomParameters();
  public abstract java.util.Map<java.lang.String, java.lang.String> getParameters();
  public abstract java.lang.String getParameterValue(java.lang.String);
  // ... existing getBranch(), getRevisions(), getBuildType() ...
}
```

**Open SDK question — needs a 5-minute spike**: how does the
precondition reach the `TriggeredBy` for the current queue item?
`BuildPromotion` does not expose it directly; the most likely paths
are `promotion.queuedBuild?.triggeredBy` (since `BuildPromotion` has
`getQueuedBuild(): SQueuedBuild?`) or a cast to `BuildPromotionEx`.
Confirm before committing.

### Proposed design

Two independent improvements, ship them together as v0.x.x:

**Improvement 1 — Honour manual triggers (Option A)**

In `DraftAwareBuildFilter.canStart`, after extracting `promotion`,
fetch the `TriggeredBy`:

```kotlin
val triggeredBy = promotion.queuedBuild?.triggeredBy   // SDK spike confirms exact path
if (triggeredBy?.isTriggeredByUser == true &&
    triggeredBy.rawTriggeredBy != "teamcity-github-bridge") {
    // User clicked Run/Re-run themselves — honour it.
    return null
}
```

The `rawTriggeredBy != "teamcity-github-bridge"` guard prevents
self-issued retriggers from `ReadyForReviewListener.enqueue` (which
passes that exact string) from accidentally bypassing the filter.
Re-runs after a `ready_for_review` flip should still go through the
normal draft check because the PR may have flipped back to draft in
the meantime — though in practice the PR is usually ready, so the
filter would let it through anyway.

**Improvement 2 — Respect custom-run param override (Option B)**

Replace the `buildType.parameters[…]` lookup with the merged view:

```kotlin
val ignoreDrafts = promotion.parameters[PARAM_IGNORE_DRAFTS]
    ?: buildType.parameters[PARAM_IGNORE_DRAFTS]
if (ignoreDrafts != "true") return null
```

`promotion.parameters` (or `promotion.getParameterValue(...)`)
combines buildType-level params with promotion-level customisations,
so a user opening "Custom Build…" and setting
`teamcity.github.bridge.ignoreDrafts = "false"` for one run will be
honoured. **Verify with javap that `promotion.parameters` actually
returns the merged view — TC has both "buildType params view" and
"promotion params view" and naming is inconsistent.**

The two improvements are complementary: Option A is the natural UX
(clicking "Run" works as expected), Option B is the power-user
escape hatch (Custom Build override).

### Alternatives considered

- **Drop the filter entirely** and rely on consumer DSL to gate
  steps with `teamcity.github.bridge.isdraft` (the runtime param
  added in v0.8.0). Rejected: that puts every opted-in buildType
  on every draft push regardless, burning agent time for
  buildTypes the consumer explicitly opted into the queue-hold
  behaviour. The current opt-in semantics are valuable.
- **Add a `tcgh.runManualAnyway` param** to opt out per buildType.
  Rejected: ceremony for no benefit — the user already clicked
  "Run", that signals intent.

### Tests to add

`DraftAwareBuildFilterTest` (new file — the filter currently has no
unit test because the canStart path needs SDK fixtures). Pure helpers
to extract:

```kotlin
companion object {
    // Pure — testable without TC SDK mocks.
    fun shouldHonourTrigger(rawTriggeredBy: String?, isTriggeredByUser: Boolean): Boolean =
        isTriggeredByUser && rawTriggeredBy != "teamcity-github-bridge"

    fun effectiveIgnoreDrafts(
        promotionOverride: String?,
        buildTypeValue: String?,
    ): Boolean = (promotionOverride ?: buildTypeValue) == "true"
}
```

Test cases:

1. User-triggered, no plugin involvement → honour trigger → return null
2. User-triggered, but `rawTriggeredBy == "teamcity-github-bridge"` (self-retrigger) → fall through to draft check
3. VCS-triggered, no user → fall through to draft check
4. Custom param override "false" + buildType default "true" → effectiveIgnoreDrafts = false → fall through
5. No override, buildType "true" → effectiveIgnoreDrafts = true → hold if draft

End-to-end on real TC:

- Open draft PR.
- Click "Run" on Linux x64 GCC (opt-in) → build runs (Improvement 1).
- Click "Run with Custom Parameters" → override `teamcity.github.bridge.ignoreDrafts="false"` → build runs (Improvement 2).
- Push a new commit (VCS trigger) → build held → ⏭️ skipped (regression check).

### Risks / edge cases

- **Snapshot dependencies**: if buildType A depends on buildType B,
  re-running A triggers B too. The trigger source on B's promotion
  is `isTriggeredBySnapshotDependency` not `isTriggeredByUser`. With
  Option A as written, B would still be held while A runs — possibly
  fine (consistent with "no draft builds") but worth a smoke test.
- **Token availability**: a user-triggered run still needs the GitHub
  App token to be resolvable. If the App is suspended, today the
  filter fail-opens (returns null). After this change, the user
  trigger path skips the check entirely, so the same behaviour. ✅
- **Cache staleness**: irrelevant — when we honour the trigger we
  skip the PR lookup altogether, so the cache isn't consulted.

### Effort

Small. Probably 60 lines including the new test file. The SDK spike
to confirm `BuildPromotion → TriggeredBy` access path may take longer
than the actual code change.

Files touched (expected):

```
src/main/kotlin/.../filter/DraftAwareBuildFilter.kt      (two small additions)
src/test/kotlin/.../filter/DraftAwareBuildFilterTest.kt  (new)
doc/configuration.md                                      (document the manual-bypass)
doc/usage-scenarios.md                                    (add "manual run on a draft PR" scenario)
CHANGELOG.md                                              (note the new behaviour)
```

### Where Owl benefits

Owl currently has ~12 opt-in buildTypes (default `ignoreDrafts="true"`),
including all the slow GCC builds, all the sanitizers except Address,
clang-tidy, and the packaging build types. Today, a developer who
wants to verify e.g. "does this still compile under Sanitizer Thread
before I take the PR out of draft?" has no clean path — they would
have to mark the PR ready, lose the held-builds visual semantic,
test, then mark draft again. After this gap closes, they just click
"Run" on the buildType.

---

## Other backlog items

These have not had a deep audit yet but are still tracked in
[development.md#roadmap](development.md#roadmap). Listed here only for
context; pick one of them up only after #A5 ships, since #A5 touches
the same `DraftAwareBuildFilter` that several of the others may want
to extend.

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
./dev test            # JUnit5 tests (currently 81, will grow)
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
  `src/main/resources/META-INF/build-server-plugin-teamcity-github-bridge.xml`.
- Logger: `Logger.getInstance(MyClass::class.java.name)` in a
  companion object; tests call `LoggerBootstrap.install()` in `init`.
- No mocking framework; stub interfaces or extract pure helpers (see
  `PrPromotionTagger.computePlan` / `DraftCheckRunReporter.buildRequest`
  / `BuildStatusCheckRunPublisher.mapBuildOutcome` for the pattern).
- Comments only when the *why* is non-obvious; don't restate the code.
- One feature per PR; update the relevant `doc/*.md` page in the same
  PR.

### Where Owl touches the plugin

The Owl repo references the three `teamcity.github.bridge.*` parameters in:

```
/data/sources/personnel/stack_owl/Owl/.teamcity/_Self/buildTypes/GlobalBuild.kt
/data/sources/personnel/stack_owl/Owl/.teamcity/Build/Build.kt
```

If you rename or remove a parameter in the plugin, search-and-replace
both files and bump the Owl team. The connection ID currently
hardcoded in Owl's DSL is `CID_392f0141078df64b20e1bb01ada5697f`.
