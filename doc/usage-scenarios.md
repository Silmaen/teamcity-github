# Usage scenarios

Concrete walkthroughs of what happens when, who fires what, and what
the operator should expect to see in the TeamCity UI.

Each scenario assumes the plugin is installed and a build
configuration is **opted in**, which today means two things (see
[configuration.md](configuration.md)):

1. The **"GitHub Bridge integration" build feature** is attached to
   the build configuration (Build Features tab). Its presence is the
   per-task opt-in; without it the plugin never touches the build.
2. The surrounding project's **GitHub Bridge** tab carries the two
   mandatory project-level parameters:
   `teamcity.github.bridge.repo` (the `owner/name` slug) and
   `teamcity.github.bridge.connectionId`. The connection id is either
   `managed` (the server-managed GitHub App created from the admin
   page) or a TeamCity connection id such as `PROJECT_EXT_42`.

Draft handling is **per build feature**, not a project parameter. The
old `teamcity.github.bridge.ignoreDrafts` opt-in parameter no longer
exists. Instead the feature exposes a `triggerOnPrDraft` checkbox
(default **on** = build drafts too). When it is **off**, draft PR
builds are suppressed; see Scenario 1.

> The scenarios below split the PR lifecycle into draft-opened,
> push-to-draft, ready transition, etc. Two ways of getting builds
> enqueued exist in parallel:
>
> - **VCS-trigger path**: TC's own VCS trigger on `+:pull/*` enqueues
>   a build on every push. If `triggerOnPrDraft` is off, the draft
>   build is then **removed from the queue** by `DraftBuildQueueCleaner`
>   (with `DraftAwareBuildFilter` as a fallback safety net). Used in
>   Scenarios 1, 2.
> - **Plugin-event path** (the "drop VCS triggers" pattern):
>   `PullRequestEventListener` reacts to `pull_request.opened`,
>   `ready_for_review`, and `synchronize` and enqueues directly. No
>   VCS trigger needed; with `triggerOnPrDraft` off, a draft is simply
>   never enqueued in the first place. See Scenario 3 — the sequence
>   diagram covers all three trigger actions. A PR opened **directly
>   as ready** is the `opened` row.

## Scenario 1: a draft PR is opened (and the BT skips drafts)

**Actor**: a developer pushes a branch and opens a draft PR, against
a build configuration whose GitHub Bridge feature has
`triggerOnPrDraft` **unchecked**.

**Expected outcome**: any build TC's VCS trigger enqueues for the
draft is **removed from the queue** by `DraftBuildQueueCleaner`, and a
**"Skipped: draft PR"** Check Run is posted on the PR. No compute is
consumed and the queue stays clean. (If `triggerOnPrDraft` were left
at its default **on**, the draft would build normally.)

```mermaid
sequenceDiagram
    actor Dev
    participant GH as GitHub
    participant TC as TeamCity
    participant Cleaner as DraftBuildQueueCleaner
    participant Gate as BridgeGate
    participant Cache as PrInfoCache
    participant API as GitHub API
    participant Rep as DraftCheckRunReporter

    Dev->>GH: git push + open draft PR #189
    GH->>TC: pull_request event<br/>(action=opened, draft=true)
    Note over TC: VCS root sees the new ref<br/>(this is handled by TC core,<br/>not by this plugin)
    TC->>TC: enqueue build for pull/189
    TC->>Cleaner: buildTypeAddedToQueue(queuedBuild)
    Cleaner->>Cache: get(repo, 189, token)
    Cache->>API: GET /repos/.../pulls/189
    API-->>Cache: {draft: true, ...}
    Cache-->>Cleaner: PrInfo(draft=true)
    Cleaner->>Gate: decide(config, "pull/189", draft=true, ...)
    Gate-->>Cleaner: SUPPRESS_DRAFT
    Cleaner->>TC: queuedBuild.removeFromQueue(reason)
    Cleaner->>Rep: postSkippedCheckRun(reason=DRAFT_PR)
    Rep->>API: POST /repos/.../check-runs (conclusion=skipped)
    Note over TC: Build gone from the queue;<br/>DraftAwareBuildFilter is only the fallback<br/>if removeFromQueue ever throws
```

On the PR's Checks tab, GitHub shows:

```
+----------------------------------------------------+
|  -  TeamCity / Build_LinuxX64_Clang                |
|     Skipped: draft PR                              |
|     (conclusion: skipped)                          |
+----------------------------------------------------+
```

The build no longer lingers in the TeamCity queue at all; the
`DraftAwareBuildFilter` `canStart` precondition is kept only as a
fallback that holds the build if `removeFromQueue` ever fails.

## Scenario 2: developer pushes a new commit to the draft

**Actor**: same developer, force-push or new commit, same BT with
`triggerOnPrDraft` off.

**Expected outcome**: same as scenario 1 - the new revision's build
is removed from the queue and a fresh "Skipped: draft PR" Check Run
is posted at the new head SHA.

```mermaid
flowchart LR
    A[git push to draft PR] --> B[TC sees new SHA]
    B --> C[enqueue new build]
    C --> D[DraftBuildQueueCleaner]
    D --> E{gate: PR still draft<br/>& triggerOnPrDraft off?}
    E -->|yes, from cache or API| F[removeFromQueue +<br/>post Skipped: draft PR]
    E -->|no| G[leave build to run]
```

The PR info cache has a short TTL (the **PR-info cache TTL** server
setting, default 60s), so a recent draft check is reused. If the TTL
has elapsed since the previous check, the plugin re-queries GitHub.
Either way, no extra compute is spent on the build itself.

## Scenario 3: any non-draft `pull_request` event fires

**Actor**: developer either clicks "Ready for review", opens a PR
directly as ready, or pushes a new commit to an already-ready PR.

**Expected outcome**: for every matching build configuration that
does **not** already have a build on this `pull/N` ref at the same
head SHA, a fresh build is enqueued. Each then passes the
`DraftBuildQueueCleaner` / `DraftAwareBuildFilter` gate because the
cache is invalidated and the PR is no longer draft.

The three trigger actions map to:

| GitHub action | Trigger | Skip path |
|---|---|---|
| `opened` (`draft: false`) | PR opened directly as ready. | Each matching BT — fresh PR has no history, so smart-skip never matches. |
| `ready_for_review` | Draft → ready transition. | Smart-skip catches builds that already ran during draft (e.g. via manual trigger) at the same SHA. |
| `synchronize` (`draft: false`) | Push to a ready PR. | Smart-skip catches duplicate deliveries on the same SHA. |

```mermaid
sequenceDiagram
    actor Dev
    participant GH as GitHub
    participant TC as TeamCity
    participant W as PluginWebhookController
    participant L as PullRequestEventListener
    participant Cache as PrInfoCache
    participant Q as Build queue
    participant Gate as BridgeGate

    Dev->>GH: open ready / mark ready / push to ready PR
    GH->>W: POST /webhook<br/>action=opened | ready_for_review | synchronize<br/>X-Hub-Signature-256: sha256=...
    W->>W: HMAC-SHA256 verify
    W->>L: handle(payload)
    L->>Cache: invalidate(repo, 189)
    L->>L: scan ProjectManager.activeBuildTypes
    Note over L: candidate = BTs with the<br/>"GitHub Bridge integration" feature whose<br/>project repo matches (case-insensitive)
    loop for each matched buildType
        L->>Gate: decide(config, "pull/189", draft, headRef, manual=false)
        Gate-->>L: ALLOW (PR is ready)
        L->>L: smart-skip if running / queued / finished<br/>build already exists at (pull/189, head SHA)
        L->>Q: addToQueue(promotion, "teamcity-github-bridge")
    end
    Q->>Q: start the builds
```

In the TeamCity UI, the build queue shows fresh entries with the
comment:

```
+----------------------------------------------------+
|  o  pull/189  Build_LinuxX64_Clang   10:31        |
|     Triggered by: teamcity-github-bridge          |
|     Comment: Retriggered by teamcity-github-      |
|              bridge after pull_request.opened on  |
|              PR #189                              |
+----------------------------------------------------+
```

## Scenario 4: PR is reverted to draft

**Actor**: developer converts back to draft.

**Expected outcome**: the plugin does nothing on the `converted_to_draft`
action. If a new commit comes in while in draft state and the BT has
`triggerOnPrDraft` off, scenario 1 applies again (queue removal +
"Skipped: draft PR"). In-flight builds are not cancelled - they
finish on the revision they were started for.

This is a deliberate design choice: the plugin never **stops a
running build**. Stopping a running build has surprising side effects
in TC (it counts as a red build on GitHub commit status). The plugin
will only **remove a not-yet-started build from the queue** (e.g. the
draft suppression in Scenario 1, or a closed PR in Scenario 18) or
**enqueue** a new one.

## Scenario 5: the GitHub App is missing a permission

**Actor**: ops accidentally removes the "Pull requests" permission.

**Expected outcome**: the plugin fails open. Builds proceed without
the draft check, with a warning in the log.

```
[INFO  ] PluginWebhookController - Registered webhook controller at /app/teamcity-github-bridge/webhook
[WARN  ] GitHubClient - GitHub returned 403 for acme/widget#189
[WARN  ] DraftBuildQueueCleaner - Cannot fetch PR info for acme/widget#189; leaving build in queue
```

```mermaid
flowchart TD
    A[Build queued for pull/189] --> B[DraftBuildQueueCleaner]
    B --> C{token resolved?}
    C -->|no| D[log warn,<br/>leave build in queue]
    C -->|yes| E{API call OK?}
    E -->|no, 403| D
    E -->|yes| F{draft & triggerOnPrDraft off?}
    F -->|yes| G[removeFromQueue +<br/>post Skipped: draft PR]
    F -->|no| H[leave build to run]
```

Fail-open is preferred to fail-closed here because:
- The plugin is non-critical for safety; missing the suppression
  costs CI minutes, not correctness.
- A hard fail would surprise teams when GitHub has an outage.

The retrigger flow (scenario 3) is unaffected because it does not
query the API - it trusts the signed webhook payload.

## Scenario 6: the webhook secret is missing

**Actor**: brand-new install, ops forgot to set `teamcity.github.bridge.webhook.secret`.

**Expected outcome**: every webhook delivery is rejected with HTTP
401 and a loud warning in the log.

```
[WARN  ] PluginWebhookController - Webhook secret is not configured (set internal property teamcity.github.bridge.webhook.secret) - refusing request
[WARN  ] PluginWebhookController - Webhook with invalid or missing signature rejected (event=ping)
```

GitHub's `Recent Deliveries` shows:

```
+----------------------------------------------------------+
|  x  ping  10:33  401  Response: Invalid signature        |
+----------------------------------------------------------+
```

This is fail-closed by design. See
[security.md](security.md#fail-closed-on-the-webhook) for the
rationale.

## Scenario 7: TeamCity restart between draft and ready

**Actor**: ops restarts TC while a PR is still in draft.

**Expected outcome**:
- In-flight builds are restored from the queue on restart.
- The PR info cache is empty after restart - the next
  `canStart` check re-queries GitHub.
- The webhook is unaffected because the GitHub App keeps
  delivering; on restart, the controller re-registers at
  `/app/teamcity-github-bridge/webhook` and resumes.

```
TC up  -> draft PR opened, builds held
TC down (5 min) -> GitHub deliveries get 5xx, GitHub retries
TC up  -> webhook re-registered, retried deliveries land,
          held builds re-evaluated, still held (PR still draft)
```

GitHub retries failed webhook deliveries up to 24h, so a brief
maintenance window is non-fatal.

## Scenario 8: build type without the bridge enabled

**Actor**: build type that does **not** have the "GitHub Bridge
integration" build feature (or whose project is missing
`teamcity.github.bridge.repo` / `connectionId`).

**Expected outcome**: nothing the plugin does affects it.

- `BridgeFeatureReader.read(buildType)` returns `null` when the
  feature is absent or the project chain lacks repo + connectionId,
  so every gate site short-circuits:
  ```kotlin
  val config = BridgeFeatureReader.read(buildType) ?: return
  ```
  -> `DraftBuildQueueCleaner` / `DraftAwareBuildFilter` leave the
  build untouched and it proceeds as usual.
- `PullRequestEventListener` only scans build types that carry the
  feature, so the build type is never enqueued by the retrigger flow.

This isolation guarantees the plugin is **safe to deploy** to a
TeamCity server: nothing changes until a team opts in by adding the
build feature and setting the project's repo + connectionId.

## Scenario 9: multi-repo project

**Actor**: a project has builds for two repos (`acme/api` and
`acme/web`), both opted in.

**Expected outcome**: a webhook for `acme/api` only retriggers
builds with `teamcity.github.bridge.repo=acme/api`. Builds for `acme/web` are
untouched.

```mermaid
flowchart TD
    A[Webhook: action=ready_for_review<br/>repository=acme/api #42] --> B[PullRequestEventListener]
    B --> C[scan all activeBuildTypes]
    C --> D{teamcity.github.bridge.repo<br/>== acme/api?}
    D -->|yes| E[enqueue]
    D -->|no| F[skip]

    G[Build: api-test ✓ enqueued]
    H[Build: api-lint ✓ enqueued]
    I[Build: web-test x not enqueued]
    J[Build: web-lint x not enqueued]
```

## Scenario 10: a build finishes (Check Run publication)

**Actor**: a build configuration opted into the bridge finishes (success or failure).

**Expected outcome**: a GitHub Check Run is posted at every
lifecycle transition (queued / running / interrupted / finished /
queue-cancelled), carrying the build's actual status text. GitHub
dedups by `(name, head_sha)` so the same row transitions through
every state.

```mermaid
sequenceDiagram
    participant Build as Build
    participant Pub as BuildStatusCheckRunPublisher
    participant TR as TokenResolver
    participant GC as GitHubClient
    participant GH as GitHub API

    Build->>Pub: buildTypeAddedToQueue
    Pub->>TR: resolveAccessToken
    Pub->>GC: postCheckRun(status=queued, details_url=<queue page>)
    GC->>GH: POST /repos/.../check-runs
    GH-->>GC: 201

    Build->>Pub: buildStarted
    Pub->>GC: postCheckRun(status=in_progress, details_url=<build page>)
    GC->>GH: POST /repos/.../check-runs
    GH-->>GC: 201

    Note over Build: build runs, agent emits<br/>##teamcity[buildStatus text='3 warnings; 0 errors']

    alt user stops the build
        Build->>Pub: buildInterrupted
        Pub->>GC: postCheckRun(status=completed, conclusion=cancelled)
    end

    Build->>Pub: buildFinished
    Pub->>Pub: mapBuildOutcome(status, isInterrupted)
    Pub->>GC: postCheckRun(status=completed,<br/>conclusion=success/failure/cancelled,<br/>summary=<status text>)
    GC->>GH: POST /repos/.../check-runs
    GH-->>GC: 201
```

What appears on the PR:

| State | GitHub UI shows |
|---|---|
| Queued | `TeamCity / <buildType full name>` with status "Expected" + clock icon, title "Queued". `details_url` points at the TC queue. |
| In progress | Same row transitions to "In progress", title "Building". `details_url` points at the TC build page. |
| Interrupted (user stops it) | Check Run "Build cancelled", conclusion `cancelled`. Posted early so the row never gets stuck at "In progress" if `buildFinished` doesn't enchain. |
| Cancelled in queue (user removes it) | Check Run "Cancelled before start", conclusion `cancelled`. Only fires for user-initiated removals (the draft-suppression cleaner is silent so its `Skipped` row stays). |
| Success | Check Run "Build passed", `output.summary` = the build's `statusDescriptor.text` (e.g. "3 warnings; 0 errors") |
| Failure | Check Run "Build failed", same summary source |
| Cancelled (finished) | Check Run "Build cancelled" (any underlying status, when `isInterrupted` is true) |

Every Check Run carries a `details_url` so the "Details" link from
the GitHub Checks tab jumps directly to the relevant TC page
instead of the server root.

> **Coexistence with the bundled `commitStatusPublisher`**: until
> you disable the bundled publisher on the opted-in build types,
> GitHub shows **both** a Commit Status (TC's, generic text) and a
> Check Run (this plugin's, rich text). Reconfigure branch
> protection rules to require the Check Run name(s) and treat the
> Commit Statuses as informational. A future iteration will provide
> a Build Feature to silence the bundled publisher per buildType.

## Scenario 11: operator visits the admin page

**Actor**: a TC admin opening the in-product help page.

**Expected outcome**: the admin lands on a self-contained dashboard
that tells them at a glance whether the plugin is healthy.

Navigation: `Administration -> Server Administration -> GitHub
Bridge`.

```
+----------------------------------------------------------+
| TeamCity GitHub Bridge                                   |
+----------------------------------------------------------+
| Getting started                                          |
|   1. Create & install the GitHub App (card below)        |
|   2. Point a project at it (repo + connectionId=managed) |
|   3. Add the "GitHub Bridge integration" build feature   |
|   4. Verify App config + Run self-tests, open a PR       |
+----------------------------------------------------------+
| Plugin status                                            |
|   Plugin version:    1.x                                 |
|   TeamCity version:  TeamCity 2026.1 (build <n>)         |
|   Webhook URL:       https://.../app/.../webhook         |
|   HMAC secret:       [configured]  [Set/Replace] [Clear] |
|   Dedicated log:     [configured] /.../...-bridge.log    |
|   Config snapshot:   JSON | Markdown                     |
+----------------------------------------------------------+
| GitHub App                                               |
|   [Create GitHub App]   (manifest pre-filled: webhook    |
|                          URL, permissions, events)       |
|   -- once configured --                                  |
|   managed App: <app-slug>   [Open settings] [Install]    |
|   connectionId=managed                                   |
|   [Verify App configuration]  (GET /app, checks perms    |
|                                & subscribed events)       |
+----------------------------------------------------------+
| Server settings (applied immediately, no restart)        |
|   API base / API version / PR-info cache TTL / grace     |
|   HTTP retry attempts + base delay                       |
|   Feature flags: replay protection, dry-run, metrics,    |
|                  legacy aliases, sticky PR comment       |
|   Repository allowlist / Comment-trigger authors         |
+----------------------------------------------------------+
| External API                                             |
|   [enabled/disabled]  API token form  [Set] [Disable]    |
+----------------------------------------------------------+
| Self-tests                                               |
|   [Run self-tests]  (see scenario 11.b)                  |
+----------------------------------------------------------+
| Recent events (last N in-memory)                         |
|   2026-05-25 14:02:30  pull_request  ready_for_review    |
|                        acme/widget   200  accepted       |
|   2026-05-25 14:01:55  ping                              |
|                                      200  accepted       |
|   ...                                                    |
+----------------------------------------------------------+
| GitHub App webhook quick-config                          |
|   (paste-ready table for the App's webhook page)         |
+----------------------------------------------------------+
| Help & documentation                                     |
|   - README                                               |
|   - Installation, GitHub App setup, Webhook setup, ...   |
|   - Common 401 / 404 troubleshooting (fold)              |
+----------------------------------------------------------+
```

The page is organised top-to-bottom as: a **Getting started** card
(the four-step opt-in), **Plugin status**, a **GitHub App** card
(one-click create from a pre-filled manifest, deep links to the App's
settings / installations on GitHub, and a **Verify App configuration**
button that calls `GET /app` and diffs the App's live permissions and
subscribed events against what the plugin needs), an editable **Server
settings** form (tuning + feature-flag checkboxes, saved to the
plugin properties file and applied without a restart), the **External
API** token form, the **Run self-tests** button, and the **Recent
events** table.

The "Recent events" table is in-memory only (ring buffer cleared on
TC restart). The dedicated log file is the long-term audit. If you do
not see any events after a webhook delivery, recheck signature and URL
with the troubleshooting fold on the same page.

The **HMAC secret form** sets or rotates the webhook secret (CSRF
protected, writes to
`<TC_DATA_DIR>/config/teamcity-github-bridge.properties`); the secret
is never echoed back. The same properties file backs the **API token**
and **Server settings** forms.

## Scenario 11.b: operator runs the self-tests

**Actor**: an admin clicks **Run self-tests** on the admin page.

**Expected outcome**: a synchronous POST validates every part of the
plugin pipeline and renders the results as a colour-coded table.

```mermaid
sequenceDiagram
    actor Admin
    participant UI as Admin page
    participant Ctl as AdminTestController
    participant T as PluginSelfTester
    participant GH as api.github.com
    participant Self as PluginWebhookController (own)
    participant TR as TokenResolver

    Admin->>UI: Click "Run self-tests"
    UI->>Ctl: POST /admin/bridge/runTests.html<br/>+ tc-csrf-token
    Ctl->>Ctl: check CHANGE_SERVER_SETTINGS perm
    Ctl->>T: runAllTests(webhookUrl)
    T->>T: passive checks (secret, log)
    T->>GH: GET /zen
    T->>T: HMAC sign + verify
    T->>Self: POST /webhook (signed ping)
    Self-->>T: 200 pong
    par for each opted-in (project, repo)
        T->>TR: resolveAccessToken(project, conn)
        T->>GH: GET /rate_limit (Bearer)
    end
    T-->>Ctl: List<TestResult>
    Ctl->>Ctl: session.setAttribute(results)
    Ctl-->>UI: 302 redirect ?bridgeResult=tested
    UI->>Admin: render PASS/WARN/FAIL/SKIP table
```

The test categories (config checks, GitHub reachability, HMAC
roundtrip, webhook self-delivery, and per-project token resolution)
are described in [api-reference.md](api-reference.md#self-tests).

Typical reading:
- All PASS: the plugin is healthy. Webhooks will land, tokens will
  resolve, builds on opted-in PRs will get Check Runs.
- "Webhook self-delivery" FAIL while the API reachability passes:
  the reverse proxy strips a header or the secret was rotated on
  one side only.
- "Token resolution" FAIL on every project: the connection ID is
  wrong (it must be `managed` for the server-managed App, or a real
  TeamCity connection id like `PROJECT_EXT_42`), the App is not
  installed on the repo, or — for a TC connection — it has never been
  "Test connected" in TC (which is what mints the first token).
- "GitHub API auth" FAIL after "Token resolution" PASS: GitHub
  rejected the token. The App's installation may have been revoked
  on the org side.

## Scenario 12: draft / ready pill rendering in TC lists

**Actor**: any user looking at a build configuration page or the
queue page.

**Expected outcome**: builds carrying the `draft` or `ready` tag
(placed by `PrPromotionTagger` - scenario 1 / 3) display a coloured
pill instead of TC's default grey chip.

```
Builds list
+----+--------------------+--------+------------------------------+
| #  | Build              | Branch | Tags                         |
+----+--------------------+--------+------------------------------+
| 87 | #87 Feature/raycast| pull/189 | [draft] (amber)            |
| 86 | #86 main           | main     |                            |
| 85 | #85 Feature/foo    | pull/188 | [ready] (green)            |
+----+--------------------+--------+------------------------------+
```

How it works: a `SimplePageExtension` registered in
`ALL_PAGES_FOOTER_PLUGIN_CONTAINER` injects a small CSS + JS
fragment into every page. The JS walks the rendered tag anchors
and adds a CSS class when the text matches `draft` or `ready`. No
network calls, no DOM dependencies beyond TC's stock tag markup;
safe to ship.

If TC ever changes the markup the styling silently stops, the page
remains intact.

## Scenario 13: queue dedup

**Actor**: a build is already running for `pull/189` when the
ready-for-review webhook arrives.

**Expected outcome**: no duplicate build. TeamCity's queue optimiser
deduplicates by (buildType, revision).

The plugin always calls `buildType.addToQueue(promotion, "teamcity-github-bridge")`;
it never tries to be clever about whether one is already running.
That decision lives in TC core.

## Scenario 14: a collaborator comments "/rebuild"

**Actor**: a repo collaborator posts an inline review comment
containing the configured trigger phrase (e.g. `/rebuild`) on an open
PR's diff; an outside contributor posts the same phrase.

**Expected outcome**: the collaborator's comment enqueues every
opted-in BuildType whose `commentTrigger` phrase appears in the body
(case-insensitive). The outside contributor's comment is ignored.

This fires on the inline PR review comment event
(`pull_request_review_comment`), which the App subscribes to by
default. General PR *conversation* comments (`issue_comment`) trigger
the same way but are **opt-in**: GitHub only delivers them when the App
has the **Issues** permission, which the plugin does not request by
default.

The author is trusted by GitHub's `author_association`: by default
`OWNER`, `MEMBER`, `COLLABORATOR` (people with write access). Anyone
else - a drive-by `NONE`/`CONTRIBUTOR` commenter - cannot start
builds.

```mermaid
sequenceDiagram
    actor Collab as Collaborator
    actor Outsider
    participant GH as GitHub
    participant W as PluginWebhookController
    participant L as PullRequestEventListener
    participant SS as BridgeServerSettings
    participant Q as Build queue

    Collab->>GH: inline review comment "/rebuild" on PR #189
    GH->>W: POST /webhook (event=pull_request_review_comment, action=created)
    W->>L: handleCommentCommand(payload)
    L->>SS: isRepoAllowed + isCommentAuthorAllowed(COLLABORATOR)
    SS-->>L: true / true
    Note over L: match BTs whose commentTrigger<br/>("/rebuild") appears in the body
    L->>Q: addToQueue(...) for each match

    Outsider->>GH: inline review comment "/rebuild" on PR #190
    GH->>W: POST /webhook (event=pull_request_review_comment)
    W->>L: handleCommentCommand(payload)
    L->>SS: isCommentAuthorAllowed(NONE)
    SS-->>L: false
    Note over L: log "Ignoring ... (association=NONE not allowed)"<br/>and return — nothing enqueued
```

The trusted set is tunable via the
`comment.allowedAssociations` setting. An empty value opens the
trigger to everyone (use with care).

## Scenario 15: a review approval triggers a gated suite

**Actor**: a reviewer clicks **Approve** on a PR.

**Expected outcome**: every opted-in BuildType that requested
**run-on-approval** (typically an expensive suite deliberately held
back until a human has approved) is enqueued. Normal
ready/synchronize gating is independent of this path.

```mermaid
flowchart TD
    A[Reviewer approves PR #189] --> B[GitHub: pull_request_review<br/>state=approved]
    B --> C[POST /webhook event=pull_request_review]
    C --> D[PullRequestEventListener.handleReviewApproved]
    D --> E{repo allowed & not draft?}
    E -->|no| X[ignore]
    E -->|yes| F[candidate BTs where<br/>runOnApproval && prTriggerEnabled<br/>&& branch matches]
    F --> G[enqueueIfAbsent on pull/189 @ head SHA]
```

A BuildType that does **not** set run-on-approval is untouched by
this event; it still participates in the normal opened /
ready_for_review / synchronize path.

## Scenario 16: re-run from the GitHub Checks UI

**Actor**: a developer clicks **Re-run** on a TeamCity Check Run in
the PR's Checks tab.

**Expected outcome**: GitHub sends a `check_run` event with
`action=rerequested`. The plugin maps the Check Run name back to its
BuildType and enqueues a fresh build - even though a finished build
already exists at that head SHA (re-running a finished build is
exactly the intent).

```mermaid
sequenceDiagram
    actor Dev
    participant GH as GitHub
    participant W as PluginWebhookController
    participant L as PullRequestEventListener
    participant Q as Build queue

    Dev->>GH: click "Re-run" on "TeamCity / Build_LinuxX64_Clang"
    GH->>W: POST /webhook (event=check_run, action=rerequested)
    W->>L: handleRerun(payload)
    L->>L: find candidate BT where checkRunName(bt) == payload.checkRunName
    Note over L: enqueueIfAbsent(..., ignoreFinished=true)<br/>skips only a currently running/queued build,<br/>never a finished one
    L->>Q: addToQueue(promotion, "...re-run requested from GitHub")
```

If the Check Run name matches no BuildType (e.g. it belongs to
another CI), the listener logs `matched no BuildType` and returns.

## Scenario 17: monorepo path filtering

**Actor**: a developer opens a PR that touches only `docs/`, in a
repo where one BuildType (`api-test`) is filtered to `src/api/**`.

**Expected outcome**: `api-test` is **skipped** with a Check Run
"Skipped: paths out of scope"; BuildTypes without a path filter (or
whose filter matches a changed file) are enqueued normally.

```mermaid
flowchart TD
    A[PR #189 changes docs/intro.md] --> B[PullRequestEventListener]
    B --> C[gate: ALLOW targets]
    C --> D{any target has a path filter?}
    D -->|no| K[enqueue all]
    D -->|yes| E[GitHubClient.listPrFiles -> changed files]
    E --> F{filter matches a changed file?}
    F -->|src/api/** vs docs/* : no| G[drop api-test<br/>post Skipped: PATH_FILTER]
    F -->|yes| H[keep & enqueue]
```

Path filtering **fails open**: if the token cannot be resolved or
the changed-files list comes back empty, all targets are kept rather
than silently swallowed. The changed-files list is fetched once,
lazily, and only when at least one target actually uses a filter.

## Scenario 18: PR is closed or merged

**Actor**: a developer merges the PR, or closes it without merging.

**Expected outcome**: every build still **queued** for that PR's
head is removed from the queue, so closing a PR mid-build stops
burning agent minutes. Running builds are left to finish (stopping
them would need an acting user and extra permissions).

```mermaid
flowchart LR
    A[PR #189 merged/closed] --> B[pull_request action=closed]
    B --> C[PullRequestEventListener.cancelQueuedForClosedPr]
    C --> D[for each candidate BT:<br/>remove queued builds on pull/189]
    D --> E[builds_cancelled counter incremented]
```

The removal comment reads `teamcity-github-bridge: PR #189 merged`
(or `closed`). The plugin still never cancels *running* builds - the
no-cancel-running stance from Scenario 4 stands.

## Scenario 19: querying status and triggering a build via the external API

**Actor**: an external tool (CI orchestrator, ChatOps bot) calls the
authenticated HTTP API. The API is enabled only when an API bearer
token has been set on the admin page; otherwise every call returns
`503`.

**Expected outcome**: `GET /api/status` returns a JSON snapshot;
`POST /api/trigger` enqueues a build.

```bash
TOKEN='the-api-token-set-on-the-admin-page'
BASE='https://<TC_HOST>/app/teamcity-github-bridge/api'

# Health/config snapshot
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/status" | jq
# -> {"pluginVersion":"<version>","secretConfigured":true,"dryRun":false,
#     "replayProtection":true,"metricsEnabled":true,"repoAllowlist":[...]}

# Recent webhook events
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/events" | jq '.events[0]'

# Counter snapshot (JSON; same numbers as /metrics in Prometheus form)
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/metrics" | jq

# Trigger a build of a BuildType on a branch
curl -s -X POST -H "Authorization: Bearer $TOKEN" \
     -H 'Content-Type: application/json' \
     -d '{"buildTypeId":"MyProject_ApiTest","branch":"pull/189"}' \
     "$BASE/trigger"
# -> {"queued":true,"detail":"queued MyProject_ApiTest on pull/189"}
```

Responses:
- `503 {"error":"API disabled (no token configured)"}` - no API
  token set on the admin page.
- `401 {"error":"invalid or missing bearer token"}` - token absent
  or wrong (compared constant-time).
- `409` with `{"queued":false,...}` - the trigger could not enqueue
  (unknown build type, etc.).

When dry-run is on, `POST /api/trigger` returns
`{"queued":true,"detail":"dry-run: would enqueue ..."}` without
adding a build.

## Scenario 20: dry-run mode

**Actor**: an operator turns on **dry-run** in the admin settings to
stage a rollout without side effects.

**Expected outcome**: every mutating action is logged with a
`[dry-run]` prefix but not performed - no builds enqueued, no builds
removed from the queue, no Check Runs or PR comments posted. Webhook
verification, candidate matching, gating, and path filtering still
run, so the log shows exactly what *would* have happened.

```
[INFO] PullRequestEventListener - [dry-run] would enqueue MyProject_ApiTest for acme/widget#189 (no build added)
[INFO] BuildStatusCheckRunPublisher - [dry-run] would POST queued Check Run for acme/widget@<sha>
[INFO] PullRequestEventListener - [dry-run] would remove queued MyProject_ApiTest for acme/widget#190
```

Dry-run is the recommended first step when enabling the bridge on a
busy server: watch the dedicated log for a few real PRs, confirm the
right BuildTypes are matched, then turn it off.

## Scenario 21: PR-metadata gate (title / body phrase + labels)

**Actor**: a developer opens (or pushes to) a PR against a build
configuration whose GitHub Bridge feature sets one or more of the
**PR-metadata** fields — `requirePhrase`, `skipPhrase`, `labelFilter`
(v1.8.0+).

**Expected outcome**: the automatic trigger is **soft-filtered** on the
PR's title, body and labels before the build is enqueued. When the PR is
out of scope, the BuildType is **not** enqueued and a **"Skipped: PR
metadata out of scope"** Check Run is posted (`SkipReason.METADATA_FILTER`).
Like the branch/path filters these are **soft**: a manual "Run" in the TC
UI always bypasses them. The gate applies to **PR builds only**.

Three independent rules, evaluated together by `BridgeGate.metadataAllows`:

| Field | Example | Effect |
|---|---|---|
| `skipPhrase` | `[skip ci]` | PR titled `Fix typo [skip ci]` → **skipped**; the build is not enqueued and the metadata Check Run is posted. |
| `requirePhrase` | `/full` | Build runs only if the PR title or body contains `/full`; otherwise → **skipped**. |
| `labelFilter` | `+:ci` | Build runs only when the PR carries the `ci` label; an unlabelled (or `-:no-ci`-matching) PR → **skipped**. |

```mermaid
flowchart TD
    A[pull_request opened/synchronize<br/>PR #189, auto trigger] --> B[BridgeGate.decide]
    B --> C{manual Run?}
    C -->|yes| R[ALLOW: metadata bypassed]
    C -->|no| D{skipPhrase in title/body?}
    D -->|yes, e.g. [skip ci]| S[SUPPRESS_METADATA]
    D -->|no| E{requirePhrase set<br/>and absent?}
    E -->|yes| S
    E -->|no| F{labelFilter set<br/>and no rule matches labels?}
    F -->|yes, e.g. labelled no-ci| S
    F -->|no| G[ALLOW: enqueue build]
    S --> H[post Skipped: PR metadata out of scope<br/>conclusion=skipped]
```

On the PR's Checks tab, an excluded build shows:

```
+----------------------------------------------------+
|  -  TeamCity / Build_LinuxX64_Clang                |
|     Skipped: PR metadata out of scope             |
|     (conclusion: skipped)                          |
+----------------------------------------------------+
```

A common pairing: leave `requirePhrase` blank, set `labelFilter` to
`+:ci` so an expensive suite runs **only** when a reviewer adds the `ci`
label, and set `skipPhrase` to `[skip ci]` so a title-tagged PR opts out
entirely. To force a run anyway, an operator clicks "Run" — the manual
trigger bypasses all three filters.

## Summary table

| Trigger | Plugin action | Build / GitHub outcome |
|---|---|---|
| Draft PR opened | Promotion tagged `draft` + skipped Check Run posted + cleaner removes from queue | Queue stays clean, pill visible, GitHub PR shows "Skipped: draft PR" |
| Push to draft | Same path again on new revision | Same |
| Marked ready for review | Listener enqueues + promotion tagged `ready` + filter allows | Builds run, ready pill visible, queued / in_progress Check Run posted |
| Build added to queue | `BuildStatusCheckRunPublisher.buildTypeAddedToQueue` | Check Run `status=queued`, title "Queued" (skipped for draft-suppressed builds so they don't race with the skipped row) |
| Build starts | `BuildStatusCheckRunPublisher.buildStarted` | Check Run `status=in_progress`, title "Building" |
| Build stopped manually (mid-run) | `BuildStatusCheckRunPublisher.buildInterrupted` (early) + `buildFinished` (final) | Check Run `status=completed, conclusion=cancelled` |
| Build cancelled while still in queue | `BuildStatusCheckRunPublisher.buildRemovedFromQueue` (only when removed by a user) | Check Run "Cancelled before start", conclusion `cancelled` |
| Build finishes (success) | `BuildStatusCheckRunPublisher.buildFinished` | Check Run `status=completed`, conclusion `success`, summary = build's `statusDescriptor.text` |
| Build finishes (failure) | Same hook, different mapping | conclusion `failure` |
| Build cancelled (final) | Same hook, `isInterrupted` short-circuits | conclusion `cancelled` |
| User clicks "Run" on draft PR | Manual trigger bypasses suppression (filter, cleaner, skipped-reporter, queued-gate all yield) | Build actually runs |
| Reverted to draft | None | In-flight builds continue, new ones held |
| API error during draft check | Logged warning | Build allowed (fail-open) |
| Missing webhook secret | Webhook rejected 401 | No retrigger; warning logged; visible in admin page recent events |
| Build type not opted in | None | No change |
| Trusted collaborator comments the trigger phrase | `PullRequestEventListener.handleCommentCommand` enqueues matching BTs | Builds run; outside commenters are ignored |
| PR review approved | `handleReviewApproved` enqueues run-on-approval BTs | Gated suites run |
| Re-run clicked in GitHub Checks UI | `handleRerun` (`ignoreFinished=true`) | Fresh build runs even past a finished one |
| PR touches only out-of-scope paths | `applyPathFilter` drops the BT, posts Skipped | GitHub PR shows "Skipped: paths out of scope" |
| PR title/body/labels out of scope (`requirePhrase`/`skipPhrase`/`labelFilter`) | `BridgeGate` returns `SUPPRESS_METADATA`, posts Skipped (auto only) | GitHub PR shows "Skipped: PR metadata out of scope"; manual Run bypasses |
| PR closed / merged | `cancelQueuedForClosedPr` removes queued builds | Queue drained; running builds finish |
| External API trigger | `triggerBuild` via `/api/trigger` | Build enqueued (or dry-run no-op) |
| Dry-run enabled | Every mutation logged `[dry-run]`, none performed | No builds/Check Runs/comments; log shows intent |

## See also

- [Security model](security.md) - why the plugin fails closed on
  webhooks and open on API errors.
- [Troubleshooting](troubleshooting.md) - what to do when a
  scenario doesn't behave as expected.
