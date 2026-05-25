# Roadmap — known gaps to close

> **Audience**: an AI agent (or human) picking this up cold.
> This document is self-contained — you do not need prior conversation
> history. File paths, SDK signatures, and design constraints are
> spelled out below.
>
> **Scope**: three concrete gaps identified during the integration of
> this plugin into the Owl project (`/data/sources/personnel/stack_owl/Owl`)
> on 2026-05-25. The summary roadmap in
> [development.md#roadmap](development.md#roadmap) lists every planned
> item; this file expands the three that came out of that integration
> audit.
>
> All three are **independently shippable** — pick any of the three and
> ship it on its own branch.

## Snapshot of the plugin (2026-05-25)

What is already wired up — read this before opening a feature.

| Component | Hook | Purpose |
|---|---|---|
| `DraftAwareBuildFilter` (`filter/`) | `StartBuildPrecondition.canStart` | Holds queued builds for draft PRs when `tcgh.ignoreDrafts=true`. |
| `ReadyForReviewListener` (`retrigger/`) | called from `PluginWebhookController` | On `pull_request.ready_for_review` webhook, re-enqueues every opted-in build type. |
| `PrBuildEnricher` (`enrich/`) | `BuildServerAdapter.buildStarted` | Once a build *starts*, sets `buildNumber = "<n> <headRef>"` and adds tag `draft`/`ready`. **Does not fire for held builds.** |
| `DraftCheckRunReporter` (`report/`) | `BuildServerAdapter.buildTypeAddedToQueue(SQueuedBuild)` | When a held draft build hits the queue, publishes a GitHub Check Run with `conclusion=skipped`. Dedup keyed on `(headSha, buildTypeExternalId)`. |
| `GitHubClient` (`api/`) | — | REST client: `getPr()`, `postCheckRun()`. Tokens are opaque strings; no length checks. |
| `PrInfoCache` (`cache/`) | — | 60s TTL in-memory cache keyed on `(repo, prNumber)`. Falls back to last known value on fetch failure. |
| `TokenResolver` (`api/`) | — | Resolves a GitHub App installation token via `OAuthConnectionsManager` + `OAuthTokensStorage`. Connection ID format is `CID_<hash>`. |

Three buildType parameters drive opt-in (see `DraftAwareBuildFilter.PARAM_*`):

- `tcgh.ignoreDrafts` (`"true"` to enable suppression)
- `tcgh.github.repo` (e.g. `Silmaen/Owl`)
- `tcgh.github.connectionId` (e.g. `CID_392f0141078df64b20e1bb01ada5697f`)

What's **not** done (intentionally, until one of the items below ships):

- Bundled `commitStatusPublisher` is left untouched — it still posts the hardcoded `"TeamCity build finished"` description on commit statuses.
- Branch display in TC build lists is still `pull/N` — no custom `BuildBranchInfoProvider`.
- Held draft builds appear in the queue without any visual marker.

---

## Gap 1 — Tag held-in-queue draft builds (small, ship first)

### Problem statement

When a draft PR triggers a build on an opted-in buildType (e.g.
`tcgh.ignoreDrafts=true`), `DraftAwareBuildFilter` holds the build in
queue indefinitely (until `pull_request.ready_for_review` fires). In
the TeamCity queue UI, that build appears identical to a build that's
merely waiting for an agent — no `draft` tag, no enriched build
number. The plugin user cannot tell at a glance which queued builds
are "deliberately held drafts" vs "agent-starved".

### Current behavior — exactly what fires

1. PR opened as draft → TC enqueues a build per opted-in buildType.
2. `DraftCheckRunReporter.buildTypeAddedToQueue(SQueuedBuild)` fires once → posts Check Run `skipped` to GitHub. ✅ User-facing on GitHub side.
3. `DraftAwareBuildFilter.canStart()` is polled repeatedly → returns `SimpleWaitReason("PR #N is draft …")`. Build never starts.
4. `PrBuildEnricher.buildStarted(SRunningBuild)` is **never called** because the build never transitions to running.
5. **Consequence**: queue UI shows `pull/N` with no tag. The TC user sees only the wait-reason tooltip on hover, which is easy to miss.

### Root cause

`PrBuildEnricher` is hooked on `buildStarted`, which is the wrong event
for held builds. The enrichment data (PR number, draft state, source
branch) is already known at enqueue time and can be applied to the
`BuildPromotion` instead of waiting for a `SRunningBuild`.

### SDK signatures (verified via `javap` on `server-openapi-2026.1.jar`)

```
public interface jetbrains.buildServer.serverSide.BuildPromotion {
  public abstract java.util.List<java.lang.String> getTags();
  public abstract void setTags(java.util.List<java.lang.String>);
  public abstract jetbrains.buildServer.serverSide.SBuildType getBuildType();
  public abstract jetbrains.buildServer.serverSide.Branch getBranch();
  public abstract java.util.List<jetbrains.buildServer.serverSide.BuildRevision> getRevisions();
}

public interface jetbrains.buildServer.serverSide.SQueuedBuild
    extends jetbrains.buildServer.serverSide.BuildPromotionOwner, ... {
  // BuildPromotionOwner.getBuildPromotion() returns BuildPromotion (already used by DraftCheckRunReporter).
}
```

`BuildPromotion.setTags(List<String>)` **replaces** the tag list, like
`SBuild.setTags`. Read existing tags first and merge.

There is also `BuildPromotion.setTagDatas(Collection<TagData>)` if you
need user-attributed tags, but for this gap the plain `setTags` is
enough.

### Proposed design

Move the **tagging** out of `PrBuildEnricher` and into a new
`PrPromotionTagger` (or extend `DraftCheckRunReporter`) that fires on
`buildTypeAddedToQueue`. Keep `PrBuildEnricher` as-is for the
**buildNumber** enrichment (which depends on the build actually
starting — and is already correct for the cases where the build runs).

Pseudo-code for the extension (drop it into `report/` or a new
`enrich/PrPromotionTagger.kt`):

```kotlin
class PrPromotionTagger(
    buildServer: SBuildServer,
    private val tokenResolver: TokenResolver,
    private val prInfoCache: PrInfoCache,
) : BuildServerAdapter() {
    init { buildServer.addListener(this) }

    override fun buildTypeAddedToQueue(queuedBuild: SQueuedBuild) {
        val promotion = queuedBuild.buildPromotion
        val branchName = promotion.branch?.name ?: return
        if (!branchName.startsWith("pull/")) return
        val prNumber = branchName.removePrefix("pull/").toIntOrNull() ?: return
        val buildType = promotion.buildType ?: return

        val repoSlug = buildType.parameters[DraftAwareBuildFilter.PARAM_REPO_SLUG] ?: return
        val connectionId = buildType.parameters[DraftAwareBuildFilter.PARAM_CONNECTION_ID] ?: return
        val token = tokenResolver.resolveAccessToken(buildType.project, connectionId) ?: return
        val pr = prInfoCache.get(RepoCoords.parse(repoSlug), prNumber, token) ?: return

        val tag = if (pr.draft) "draft" else "ready"
        val current = promotion.tags
        if (current.contains(tag)) return
        promotion.setTags(current + tag)
    }
}
```

Once this is in:

- `PrBuildEnricher.buildStarted` no longer needs to tag (the tag is
  already on the promotion when the build starts → propagates to
  `SBuild.getTags()` for free). Reduce `PrBuildEnricher` to just
  `setBuildNumber`. **Verify the inheritance**: an `SBuild`'s `getTags`
  may or may not surface promotion tags automatically — confirm before
  deleting code from the enricher. If it does not, leave the enricher
  tagging path as a fallback.

### Alternatives considered

- **Tag from `DraftAwareBuildFilter.canStart`**: rejected — the
  precondition is polled repeatedly and the call is performance-
  sensitive (runs inside the queue scheduler loop).
- **Tag from `buildPromotionSettingsFinalized`**: would work but fires
  before the build is in the queue UI, so the user might not yet have
  a chance to see the tag matter. `buildTypeAddedToQueue` is more
  natural.

### Test plan

Reuse the stub pattern from `PrInfoCacheTest`:

- Pure helper `computeTag(branchName, isDraft): String?` — unit-test 4
  cases (pull/, main, draft true, draft false).
- Integration verification on a real TC instance: queue a draft PR
  build, check the queue UI shows the tag; mark the PR ready, check
  the new build comes up with `ready` instead.

### Risks / edge cases

- A build re-enqueued by `ReadyForReviewListener` will have the same
  promotion id? No — `BuildCustomizer.createPromotion()` makes a new
  promotion. So new event → new tag computed from current draft state.
  ✅ correct by construction.
- Concurrent enqueues of the same PR: the tag list `setTags` call is
  not atomic with the read; but tags are eventually-consistent and a
  duplicate-add is a no-op thanks to `current.contains(tag)`.

### Effort

Small. Approximately 80 lines including a unit test.
Files touched:

```
src/main/kotlin/.../enrich/PrPromotionTagger.kt        (new)
src/test/kotlin/.../enrich/PrPromotionTaggerTest.kt    (new)
src/main/kotlin/.../enrich/PrBuildEnricher.kt          (optionally trim tagging logic)
src/main/resources/META-INF/build-server-plugin-tcgh-bridge.xml  (+1 bean)
```

---

## Gap 2 — Branch display customization in TeamCity build lists

### Problem statement

In every TC build-list view (project home, build configuration page,
queue, agent page), the **Branch** column for a PR build shows the
literal git ref captured by the VCS root branchSpec — typically
`pull/190` or `pull/190/head`. This number is the PR number, which is
fine, but it loses two pieces of information that are immediately
relevant to a reviewer:

1. The **source branch name** (e.g. `feature/raycasting-walls`).
2. The **draft / ready** state of the PR.

In a feature branch column, a row reading

```
#42  pull/190
```

would ideally read

```
#42  pull/190  feature/raycasting-walls  [draft]
```

### Current TC limitations (verified against the SDK)

TeamCity 2026.1's VCS root branchSpec syntax allows a single capture
group:

```
+:refs/heads/(%owl_git_branch%)     → captures "main"
+:refs/(pull/*)/head                → captures "pull/190"
```

The captured string is what the Branch column shows. There is **no**
way to compute it from PR metadata via DSL — the branchSpec sees only
the ref string.

The mechanism the bundled bundled-but-now-orphaned `Nicologies/PrExtras`
plugin used was a `BuildBranchInfoProvider` extension. Its repo went
read-only ~7 years ago and the source no longer compiles against
modern TC. This plugin is the place to implement an equivalent.

### SDK signatures to verify

Probable extension point — **needs `javap` confirmation before
implementing**:

```
jetbrains.buildServer.serverSide.BuildBranchInfoProvider
  (find via:  jar tf server-openapi-2026.1.jar | grep -i BranchInfo)
```

Adjacent classes worth probing:

```
jetbrains.buildServer.serverSide.Branch
jetbrains.buildServer.serverSide.BuildPromotion.getBranch()
jetbrains.buildServer.web.openapi.BuildBranchUiInfoProvider   (UI side)
jetbrains.buildServer.serverSide.BranchEx
```

Run from `./dev shell`:

```bash
jar tf '/workspace/.cache/m2/org/jetbrains/teamcity/server-openapi/2026.1/server-openapi-2026.1.jar' | grep -iE 'Branch(Info|Display|UiInfo)'
javap -cp '/workspace/.cache/m2/org/jetbrains/teamcity/server-openapi/2026.1/server-openapi-2026.1.jar' -p jetbrains.buildServer.serverSide.BuildBranchInfoProvider
```

If `BuildBranchInfoProvider` is no longer on the classpath in 2026.1,
look for `BranchDisplayNameProvider`, or surface the source branch via
a custom `PageExtension` that renders into the branch column from
client-side JS — uglier but doable.

### Proposed design

1. Implement a server-side extension that, given a `BuildPromotion` or
   `Branch`, looks up the PR (already cached via `PrInfoCache`) and
   returns an enriched display name.
2. Fallback to the raw branchSpec capture when:
   - the branch is not `pull/N`,
   - the PR is not findable in cache or via API,
   - the `tcgh.*` parameters are not present on the buildType.

The cache is already populated by `DraftAwareBuildFilter` /
`DraftCheckRunReporter` for opted-in buildTypes, so the display
provider would just read from it (no extra API calls in the common
case).

Display format suggestion (configurable later if needed):

```
pull/190  feature/raycasting-walls
```

with the literal string `[draft]` appended for draft PRs. Avoid using
icons in this column — TC's UI does not render markdown / HTML here.

### Alternatives considered

- **Custom DSL parameter set per build** that prefixes the build name
  with the source branch: rejected, mixes build number and branch into
  the same UI cell.
- **Set the build comment via `setBuildComment(User, String)`**:
  doesn't help — comments are in a different UI surface.
- **Override `BuildPromotion.getBranchName()`**: not API.

### Test plan

- Unit-test the **display string formatter** (pure function).
- End-to-end on a real TC instance: open a draft PR, confirm the
  Branch column reads `pull/N  <source-branch>  [draft]`; flip to
  ready, confirm `[draft]` disappears.

### Risks

- The TC UI caches branch display strings per build promotion. If the
  PR state changes (draft → ready) without a new commit, the cached
  display may be stale until the next reload. Acceptable for v1, but
  document the limitation.
- The `BuildBranchInfoProvider` SPI is **not** part of the public
  `server-openapi`; it likely lives in `server-api` (internal). If it
  has moved or been renamed in 2026.1, you may need to fall back to a
  Page Extension that injects JS to mutate the column client-side.
  Confirm via the bytecode dump first.

### Effort

Medium. The unknown is whether the SPI is still public. Plan a
spike of ~1 day to confirm before committing to the design.

Files (expected):

```
src/main/kotlin/.../display/PrBranchDisplayProvider.kt    (new)
src/test/kotlin/.../display/PrBranchDisplayFormatterTest.kt (new)
src/main/resources/META-INF/build-server-plugin-tcgh-bridge.xml  (+1 bean)
```

---

## Gap 3 — Enriched commit status publisher

### Problem statement

TeamCity 2026.1's bundled `commitStatusPublisher` posts a commit status
to GitHub when a build finishes, with a **hardcoded** description
string (`DefaultStatusMessages.BUILD_FINISHED` = `"TeamCity build
finished"`). The build's actual status text — set on the agent side via

```
##teamcity[buildStatus status='SUCCESS' text='3 warnings; 0 errors']
```

is shown in the TC build summary but **never propagated to the GitHub
commit status description**. Reviewers on GitHub see only "TeamCity
build finished" regardless of how the build actually performed.

This affects every opted-in buildType on every PR — it's the single
biggest UX gap of the integration today.

### Why TC behaves this way

The bundled `commitStatusPublisher` is a generic implementation
serving GitHub, GitLab, Bitbucket, Azure DevOps, … Adding a
provider-specific feature (free-form description from a build
parameter or service message) was deemed too narrow for the bundled
plugin. The roadmap entry has existed in the TC issue tracker for
years and is unlikely to land in 2026.x.

This plugin is the place to fix it for GitHub specifically.

### Two implementation paths

**Path A — Replace the publisher for opted-in build types.**

Disable the bundled publisher for buildTypes where the three `tcgh.*`
parameters are set, and have this plugin own commit status publishing
end-to-end.

- Hook on `BuildServerAdapter.buildFinished(SRunningBuild)` (and
  `buildStarted` for in-progress states).
- Read `build.statusDescriptor.text` — this carries the agent's
  `buildStatus text=...` value (verified in TC SDK).
- POST to `/repos/{owner}/{repo}/statuses/{sha}` with the captured text.

Pros: clean, single source of truth.
Cons: disabling the bundled publisher per-buildType is itself a
research item — there's no DSL setting for "skip bundled publisher for
this build". You may need to provide a Build Feature that suppresses it,
or use a `BuildPromotionEx` hook to remove the publisher feature
descriptor at runtime (fragile).

**Path B — Publish Check Runs alongside, deprecate commit statuses for opted-in builds.**

Already started in `DraftCheckRunReporter`. Extend it to publish Check
Runs at every state transition (queued, started, finished). Tell users
to set up GitHub branch protection to require the **Check Runs**
(plugin-published) and **not** the Commit Statuses (TC-published), so
the bundled publisher's hardcoded text becomes ignorable noise.

Pros: doesn't fight the bundled publisher; uses the richer Check Runs
API (multi-line output, links, summary).
Cons: GitHub PR UI shows both Check Runs and Commit Statuses by
default, so users will see redundant entries. Tooling like Mergify or
required-checks lists need to be reconfigured to point at the new
names.

### Recommended approach

**Path B first** (additive, low risk), then revisit Path A once Path B
proves out the Check Runs flow. Path B reuses the `GitHubClient`
already extended for the draft-skipped Check Run; the new code is
"publish more Check Runs at other build lifecycle events" — small.

### Sketch (Path B)

```kotlin
class BuildStatusCheckRunPublisher(
    buildServer: SBuildServer,
    private val tokenResolver: TokenResolver,
    private val gitHubClient: GitHubClient,
) : BuildServerAdapter() {
    init { buildServer.addListener(this) }

    override fun buildStarted(build: SRunningBuild) {
        publish(build, CheckRunConclusion.NEUTRAL, status = "in_progress",
                title = "Building", summary = build.statusDescriptor.text ?: "")
    }

    override fun buildFinished(build: SRunningBuild) {
        val (conclusion, title) = when {
            build.buildStatus.isSuccessful -> CheckRunConclusion.SUCCESS to "Build passed"
            build.isInterrupted            -> CheckRunConclusion.CANCELLED to "Build cancelled"
            else                           -> CheckRunConclusion.FAILURE to "Build failed"
        }
        publish(build, conclusion, status = "completed",
                title = title, summary = build.statusDescriptor.text ?: "")
    }
    // … shared `publish(...)` that resolves SHA + token, posts to GitHub
}
```

Note the **new `status` field** sent to GitHub — Check Runs accept
`queued`, `in_progress`, or `completed`. The current `GitHubClient.encodeCheckRunPayload`
hardcodes `"completed"` (it was sized for skipped drafts only). Lift
that into the request.

### Test plan

- Unit-test the **status mapping** (TC `Status` → Check Run
  `conclusion` + `status` + `title`).
- Verify the JSON payload encodes correctly with the new `status`
  field.
- E2E: trigger a build that fails on purpose (e.g. `exit 1` in a
  script step), confirm GitHub shows a Check Run with the actual error
  text from the build log summary.

### SDK signatures to verify

```
jetbrains.buildServer.serverSide.SRunningBuild.getStatusDescriptor() → BuildStatusDescriptor
BuildStatusDescriptor.getText(): String
BuildStatusDescriptor.getStatus(): Status
Status.isSuccessful: Boolean
Status.isFailed: Boolean
```

### Risks

- The bundled publisher will keep publishing commit statuses unless
  disabled. Reviewers will see two rows per buildType in the PR UI
  until Path A lands. Document this in `usage-scenarios.md`.
- Build description from `buildStatus text='…'` is set on the agent
  before the build finishes; the server-side `buildFinished` hook fires
  after, so the text is always available when we read it. **But** test
  this assumption against a real TC — the order can be subtle.
- GitHub's Check Run `output.summary` has a 65535-char limit. If the
  TC status text ever approaches this (unlikely for our use case),
  truncate. Add a guard.

### Effort

Medium. ~250 lines including tests, assuming `BuildStatusDescriptor`
is on the public SDK (verify first).

Files (expected):

```
src/main/kotlin/.../report/BuildStatusCheckRunPublisher.kt   (new)
src/test/kotlin/.../report/BuildStatusCheckRunPublisherTest.kt (new)
src/main/kotlin/.../api/GitHubClient.kt                       (lift "status" out of hardcoded)
src/test/kotlin/.../api/CheckRunPayloadTest.kt               (extend existing test)
src/main/resources/META-INF/build-server-plugin-tcgh-bridge.xml  (+1 bean)
```

---

## Cross-cutting concerns

### Validation against a real TeamCity instance

None of these gaps can be fully validated by unit tests. After each
gap is closed, install the rebuilt zip on a staging TC instance with
the Owl project's parameters wired up and run the **manual smoke
sequence** below. Document the result in the PR description.

Smoke sequence (15 minutes per gap):

1. Open a draft PR in the Owl repo with a one-line change.
2. Verify TC queue UI: held builds carry the appropriate marker
   (`draft` tag for gap #1, source branch in column for gap #2,
   richer check run summary for gap #3).
3. Mark the PR ready for review.
4. Verify the ready-for-review retrigger fires; rebuild appears with
   `ready` tag.
5. Verify GitHub PR view matches expectation.
6. Push a new commit; verify enrichment re-applies to the new
   revision.

### Local development workflow recap

```bash
cd /data/sources/Sources/IT/teamcity-github
./dev test            # JUnit5 tests (currently 39, will grow)
./dev package         # produces target/teamcity-github-bridge-*.zip
./dev shell           # interactive bash in the maven container
```

SDK introspection from the dev shell (used heavily for the audits
above):

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
  `PrBuildEnricher.computePlan` / `DraftCheckRunReporter.buildRequest`
  for the pattern).
- Comments only when the *why* is non-obvious; don't restate the code.
- One feature per PR; update the relevant `doc/*.md` page in the same
  PR.

### Where the Owl side touches the plugin

The Owl repo references the three `tcgh.*` parameters in:

```
/data/sources/personnel/stack_owl/Owl/.teamcity/_Self/buildTypes/GlobalBuild.kt
/data/sources/personnel/stack_owl/Owl/.teamcity/Build/Build.kt
```

If you change a parameter name in the plugin, search-and-replace both
files and bump the Owl team. The connection ID currently hardcoded in
Owl's DSL is `CID_392f0141078df64b20e1bb01ada5697f`.

---

## Sequencing

Recommended order if you're doing all three:

1. **Gap 1** first — small, low-risk, immediate UX win in the TC queue.
2. **Gap 3** Path B — additive, reuses the Check Runs infrastructure
   you just built, lets users opt into richer GitHub UI.
3. **Gap 2** last — has an SDK-availability unknown that warrants a
   short spike before committing.

After all three: revisit roadmap items 3 & 4 in
[development.md#roadmap](development.md#roadmap) — they may be merged
or rewritten given what you've learned.
