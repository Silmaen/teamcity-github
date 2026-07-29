# Troubleshooting

A reference for diagnosing common issues. Symptoms first, causes
second, fix third.

## Where to look first

Three quick triage stops, in order, before you dig into logs:

1. **Admin page "Recent events" table.** `Administration -> Server
   Administration -> GitHub Bridge` shows the last N webhook events
   the plugin actually processed (in-memory; full history in the
   dedicated log). Empty when GitHub never reached the plugin.
2. **`GET /app/teamcity-github-bridge/health`** — liveness JSON for
   load balancers and a quick "is the plugin alive" check. Returns
   `{"status":"ok", ...}` when the webhook secret is configured,
   `"degraded"` when it is not (always HTTP 200 — the status field
   is the signal, not the HTTP code).
3. **`GET /app/teamcity-github-bridge/metrics`** — Prometheus counters
   (`bridge_<name>_total`). Confirms enqueues, cancellations, API
   calls etc. are actually happening. Returns 404 when metrics export
   is disabled (see [/metrics returns 404](#symptom-metrics-returns-404)).

```mermaid
flowchart TB
    S1["<b>1. Self-test button — start here</b><br/>Admin → Server Admin → GitHub Bridge → Run self-tests<br/>the PASS/WARN/FAIL/SKIP table localises the broken step"]
    S2["<b>2. Recent events table, /health, /metrics</b><br/>Admin → Server Admin → GitHub Bridge"]
    S3["<b>3. /info endpoint</b> — one-shot config snapshot<br/>secretConfigured, logConfigured, payloadUrl, logFile"]
    S4["<b>4. Dedicated plugin log</b><br/>&lt;TC_DATA_DIR&gt;/logs/teamcity-github-bridge.log"]
    S5["<b>5. Server log fallback</b>, if the dedicated log was overridden<br/>&lt;TC_DATA_DIR&gt;/logs/teamcity-server.log<br/>grep io.github.dlachouette or teamcity-github-bridge"]
    S6["<b>6. GitHub App 'Recent Deliveries' panel</b><br/>did GitHub even send it, and what did we answer?"]
    S7["<b>7. TeamCity queue UI</b><br/>read the wait reason on held builds"]

    S1 -- "not conclusive" --> S2 -- "not conclusive" --> S3 -- "not conclusive" --> S4
    S4 -- "nothing logged" --> S5 -- "nothing logged" --> S6 -- "delivery accepted,<br/>but no build" --> S7
```

The commands behind steps 2, 3 and 6:

```bash
curl https://<TC_HOST>/app/teamcity-github-bridge/health
curl https://<TC_HOST>/app/teamcity-github-bridge/metrics
curl https://<TC_HOST>/app/teamcity-github-bridge/info
# Recent Deliveries: https://github.com/settings/apps/<your-app>/advanced
```

### How opt-in works (the model everything below assumes)

A BuildType participates only when **both** of these are true:

1. The **"GitHub Bridge integration" build feature** is present (and
   enabled) on the BuildType — directly or inherited from a BuildType
   template.
2. The surrounding **project** sets, on its GitHub Bridge tab,
   `teamcity.github.bridge.repo` (the `owner/name` slug, matching
   GitHub's `repository.full_name`) and
   `teamcity.github.bridge.connectionId`.

`connectionId` is either:

- **`managed`** — the server-managed GitHub App created through the
  plugin's admin manifest flow (`Administration -> Server
  Administration -> GitHub Bridge`), or
- a **TeamCity connection id** — `PROJECT_EXT_<N>` or `CID_<hash>` —
  pointing at a GitHub App connection visible from the project chain.

Draft behaviour is **not** a parameter. It is the per-feature
**`triggerOnPrDraft`** checkbox (with **`triggerOnPrReady`** as its
prerequisite — `triggerOnPrDraft` is only honoured when
`triggerOnPrReady` is on). With `triggerOnPrDraft` off, an **automatic**
trigger on a draft PR is removed from the queue and reported as a
**"Skipped: draft PR"** Check Run; an explicit Run or GitHub command on the
same draft PR runs normally (1.9.0+).

Two more things the bridge never does, worth knowing when a build behaves
unexpectedly: it never removes a build **it did not enqueue itself** except in
the documented automatic cases, and it never touches a build configuration
that does not carry its build feature.

## Symptom: self-test shows "Token resolution" FAIL on every project

### What you see

After clicking **Run self-tests** the rows
`Token resolution / <project> / <repo>` are all FAIL with detail
`TC could not produce an installation token.`

### Likely causes (in order of frequency on a vanilla TC 2026.1)

| Cause | Fix |
|---|---|
| `connectionId=managed` but no managed App is configured | The `managed` sentinel mints from the server-managed App. If none was set up, the resolver fails with `connectionId is 'managed' but no managed GitHub App is configured.` Run the manifest flow at `Administration -> Server Administration -> GitHub Bridge` to create one (it stores the App ID, slug and private key). |
| The (managed or connection) App is not installed on the target repository's owner (org or user account) | Visit `https://github.com/settings/apps/<your-app>/installations` and install the App on the owner of the repo named in `teamcity.github.bridge.repo`. |
| The App's permissions do not cover the repo (e.g. `Checks: Write` missing) | Add at minimum `Pull requests: Read`, `Checks: Write`, `Contents: Read`, `Metadata: Read`; accept the permission update on the App's installation page. |
| GitHub Enterprise Server: the API base override is not set | A managed App on GHES must reach the enterprise API, not `api.github.com`. Set the `api.base` override in the admin settings (`Administration -> Server Administration -> GitHub Bridge`) to your `https://<ghes-host>/api/v3` base. The log shows the `apiBase used: ...` value on a failed mint. |
| The connection's `appId` or `secure:privateKey` parameter is missing or empty (manual edit of the project file?) | Open `Project -> Connections -> Edit`, paste the App ID and private key, save. The self-mint path needs both. The plugin logs `Connection PROJECT_EXT_N does not expose the GitHub App credentials this plugin needs` when this happens. |
| The private key cannot be parsed (truncated, wrong format, mangled by a copy-paste) | Re-paste the `.pem` file content as-is. The plugin accepts both `-----BEGIN PRIVATE KEY-----` (PKCS#8) and `-----BEGIN RSA PRIVATE KEY-----` (PKCS#1). The log entry is `Could not parse the private key stored on connection PROJECT_EXT_N`. |
| The `teamcity.github.bridge.connectionId` value points at a project the connection is not visible from | Confirm in TC: `Project -> Connections` should list the connection on the project's own page or on one of its parents. Note: when `connectionId=managed` no project connection is needed at all — the credentials live in the plugin's admin settings. |

The dedicated log file carries one warning per failed
(project, connection, repo) triple, with the exact reason. The
log entry that confirms the self-mint path worked is:

```
INFO  AppTokenMinter - Minted fresh installation token for App #<id>
       (installation #<n>, owner=<owner>) via the self-mint path.
```

### Note on the SDK paths

Since v1.2.0 the plugin tries two token-acquisition paths in
order, with self-mint as the authoritative source:

1. **`AppTokenMinter.mint(...)` — primary.** Signs an RS256 JWT
   with the App's private key and calls
   `POST /app/installations/{id}/access_tokens` directly. Tokens
   are guaranteed fresh and scoped to the right installation.
2. `ProjectConnectionCredentialsManager.requestConnectionCredentials` —
   forward-compat fallback. Refused on TC 2026.1 for the
   `GitHubApp` provider type (`Unsupported Connection Provider
   type`). Logged once at INFO per provider type per server
   lifetime.

The `OAuthTokensStorage.getProjectTokens` cache-only fallback was
removed in v1.2.0: it used to return stale tokens that GitHub
then 401-rejected, producing exactly the "Token resolution PASS /
GitHub API auth FAIL" pattern that motivated this rework. The
self-mint path always returns a freshly-minted token.

## Symptom: plugin does not load

### What you see

`Administration -> Plugins List` does not show "TeamCity GitHub
Bridge", or the entry is greyed out. The server log has no line
matching `TeamCity GitHub Bridge plugin loaded`.

### Likely causes

| Cause | Fix |
|---|---|
| Archive in the wrong place | The archive must be at `<TC_DATA_DIR>/plugins/teamcity-github-bridge-*.zip` (note: **TeamCity Data Dir**, not the install dir). |
| Wrong TeamCity version | The plugin requires build 222521 or newer. Check `Administration -> Diagnostics`. |
| Server cache stale after upload | Restart TC. The hot-upload path requires a restart for plugins declaring `use-separate-classloader="true"`. |
| ZIP corrupted during transfer | `unzip -l <archive>` should list `teamcity-plugin.xml` and `server/*.jar`. Rebuild with `./dev package` if needed. |
| Min-build mismatch | The plugin descriptor declares `<min-build>222521</min-build>`. Older servers refuse the plugin silently with a line like `Plugin requires server build at least 222521`. |

### Verify

```bash
grep -i "teamcity-github-bridge\|teamcity github bridge\|plugin.*222521" <TC_DATA_DIR>/logs/teamcity-server.log
```

## Symptom: 401 with `WWW-Authenticate: Basic realm="TeamCity"`

### What you see

```
$ curl -i https://<TC>/app/teamcity-github-bridge/info
HTTP/2 401
www-authenticate: Basic realm="TeamCity"
www-authenticate: Bearer realm="TeamCity"

Authentication required
To login manually go to "/login.html" page
```

### Cause

TeamCity protects every path under `/app/*` by default. If you are
running an old plugin build that did not register the controller
with `AuthorizationInterceptor.addPathNotRequiringAuth(...)`, the
auth filter rejects every request before the plugin code runs.

### Fix

Upgrade the plugin to a build that includes the
`AuthorizationInterceptor` registration. Rebuild with `./dev
package`, drop the new zip in `<TC_DATA_DIR>/plugins/`, restart.

Distinguish this 401 from the HMAC 401: this one comes from
TeamCity itself (response body says `Authentication required`),
the HMAC one comes from the plugin (response body says `Invalid
signature`).

## Symptom: every webhook fails with 401 `Invalid signature`

### What you see

GitHub `Recent Deliveries` shows:

```
x  pull_request  10:33  401  Response: Invalid signature
```

The server log shows:

```
WARN  - PluginWebhookController - Webhook with invalid or missing signature rejected (event=pull_request)
```

### Likely causes

| Cause | Fix |
|---|---|
| `teamcity.github.bridge.webhook.secret` not set | Add the property in `<TC_DATA_DIR>/config/internal.properties`. |
| Secret on GitHub differs from TC | Re-paste the same value on both sides and save. |
| Reverse proxy rewrites the body | The signature is computed over the **raw bytes**. Disable body rewriting in your ingress (nginx `proxy_set_body`, AWS ALB content-modifying rules, etc.). |
| Wrong header name on GitHub | Should be `X-Hub-Signature-256` (sent by default). If you wrote `X-Hub-Signature` only, the older SHA1 header is ignored. |

### Verify

```bash
# Is the secret read?
curl -s https://<TC_HOST>/app/teamcity-github-bridge/info | jq '.secretConfigured'
# expect: true

# Test manually with a known body
secret='your-secret-here'
body='{"action":"ping"}'
expected=$(printf '%s' "$body" | openssl dgst -sha256 -hmac "$secret" | awk '{print "sha256="$2}')
echo "Expected header: $expected"
```

## Symptom: draft PR builds still run

### What you see

A PR with `draft: true` on GitHub leads to a green build in
TeamCity, instead of being removed from the queue and reported as a
"Skipped: draft PR" Check Run.

### Likely causes

| Cause | Fix |
|---|---|
| The BuildType's feature has `triggerOnPrDraft` left **on** | The draft gate only suppresses when the **"GitHub Bridge integration"** feature has `triggerOnPrDraft` **unchecked** (and `triggerOnPrReady` checked). Open `Edit Configuration -> Build Features -> GitHub Bridge integration` and uncheck "trigger on draft PRs". |
| The BuildType has no "GitHub Bridge integration" feature at all | Without the feature the BuildType is not opted in; nothing gates it. Add the feature (or inherit it from a template — needs 1.6.0+). |
| The project does not set `teamcity.github.bridge.repo` | Set it on the project's GitHub Bridge tab; the slug must match `repository.full_name` from GitHub. With no repo the config never resolves and the gate is skipped. |
| The project's `teamcity.github.bridge.connectionId` is wrong/empty | Set `managed` or a valid `PROJECT_EXT_<N>` / `CID_<hash>`. With no token the plugin cannot fetch PR draft state and fails open. The log shows `Cannot resolve token`. |
| GitHub App lacks `Pull requests: read` permission | API returns 403; plugin logs the warning and fails open. Add the permission and accept it on the App page. |
| Branch does not look like `pull/N` | The gate only treats `pull/<number>` branches as PRs. Check the VCS root's branchSpec. |
| Build was triggered manually by an operator | Intentional since 1.9.0: an explicit request — a Run in TeamCity, a comment command, a Re-run button — bypasses the draft rule *and* the branch/metadata filters, and is never removed from the queue. Only the automatic path is filtered. If an operator's draft build surprises you, that is TeamCity permissions territory, not the bridge's. |

### Verify

```bash
# Watch the live filter behaviour
tail -f <TC_DATA_DIR>/logs/teamcity-server.log | grep DraftAwareBuildFilter
```

You should see one of:

- `Suppressing build of <buildType> for draft PR <repo>#<n>` (works)
- `Cannot resolve token for <buildType>; allowing build to proceed`
- `Cannot fetch PR info for <repo>#<n>; allowing build to proceed`

If neither appears, the gate is not even reaching this build: confirm
the feature is present, the project repo + connectionId are set, and
the branch name matches `pull/<number>`.

## Symptom: PR marked ready, but no retrigger happens

### What you see

A PR transitioned from draft to ready in GitHub, the `Recent
Deliveries` panel shows a `200 OK`, but no new builds appear in the
queue.

### Likely causes

| Cause | Fix |
|---|---|
| `teamcity.github.bridge.repo` slug does not match GitHub's `repository.full_name` | Compare case-sensitively. GitHub normalises owner/name casing differently in some places. |
| The BuildTypes are not opted in | The listener only enqueues BuildTypes whose **"GitHub Bridge integration"** feature resolves against the project's `repo` + `connectionId`. Confirm the feature is present (or template-inherited on 1.6.0+) and the project params are set. |
| The matching BuildTypes do not run on ready PRs | Each candidate needs `triggerOnPrReady` on (it is on by default). With it off the bridge never triggers the build configuration from a PR event — an explicit Run or command still works, and still reports. |
| The PR's source branch is excluded by the branch filter | The `prTrigger` branch list (project-level, or the per-feature override) must match the PR head ref. An empty list matches every branch. |
| Build queue optimiser deduped against an existing build | A build for `pull/N` on the same revision may already be running. Check the running builds list. |
| Build types are paused | `ProjectManager.activeBuildTypes` excludes paused. Unpause or use a sibling build type. |

### Verify

```bash
grep PullRequestEventListener <TC_DATA_DIR>/logs/teamcity-server.log | tail -20
```

You should see, on each event:

```
INFO  - Handling pull_request.<action> for <repo>#<n> (draft=<bool>)
INFO  - Retriggering N build type(s) for <repo>#<n> on pull_request.<action>
```

If `N=0`, the filter found no matching configurations; verify the
parameters.

## Symptom: build skipped with "Skipped: PR metadata out of scope"

### What you see

A PR build is not enqueued and its GitHub Check Run reads
**"Skipped: PR metadata out of scope"** (conclusion `skipped`,
`SkipReason.METADATA_FILTER`), even though the branch and paths are in
scope and the PR is ready.

### Cause

The BuildType's **"GitHub Bridge integration"** feature has one or more
of the **PR-metadata** filters set (v1.8.0+), and the PR's title, body
or labels did not satisfy them. `BridgeGate.metadataAllows` excludes the
build when:

- `skipPhrase` is set and appears in the PR **title or body** (e.g.
  `[skip ci]`), **or**
- `requirePhrase` is set and is **absent** from the title/body, **or**
- `labelFilter` is set and **no rule matches** the PR's label names
  (`+:ci` requires the `ci` label; `-:no-ci` skips when `no-ci` is
  present).

These are **soft** filters enforced for **automatic PR triggers only**.

### Fix

| Cause | Fix |
|---|---|
| PR title/body contains the `skipPhrase` | Remove the phrase (e.g. drop `[skip ci]` from the title) and push, or edit the feature's `skipPhrase` if it is too broad. |
| The `requirePhrase` is missing from the PR | Add the phrase to the PR title or description, or clear `requirePhrase` on the feature. |
| The `labelFilter` rules do not match the PR's labels | Add the required label (e.g. `ci`) or remove the excluding label (e.g. `no-ci`); or adjust the `+:`/`-:` rules on the feature. |
| You actually want this build now | Click **Run** in the TC UI — a **manual trigger always bypasses** the metadata filters (as it does the branch/path filters). |

Check the current values under `Edit Configuration -> Build Features ->
GitHub Bridge integration` (the *PR metadata* fields), and compare them
against the PR's title/body/labels on GitHub. Remember the title **and**
body are matched together (case-insensitive substring), and labels are
matched against their **names**.

### Verify

```bash
grep -E "SUPPRESS_METADATA|metadata out of scope" <TC_DATA_DIR>/logs/teamcity-github-bridge.log | tail
```

A build that passed the metadata gate is enqueued normally; one that was
excluded shows the suppression and the posted skipped Check Run.

## Symptom: 404 on `/app/teamcity-github-bridge/info`

### What you see

```
$ curl -i https://<TC_HOST>/app/teamcity-github-bridge/info
HTTP/1.1 404 Not Found
```

### Likely causes

| Cause | Fix |
|---|---|
| Plugin not loaded | See [plugin does not load](#symptom-plugin-does-not-load). |
| Reverse proxy strips the `/app/...` prefix | TC routes are under `/app/...`. The proxy must pass them through verbatim. |
| Wrong host or port | The plugin registers the controller globally; the URL is whatever TC's root is. |

### Verify

```bash
# From the TC host itself, bypassing any proxy
curl -i http://localhost:8111/app/teamcity-github-bridge/info
```

## Symptom: `UnsupportedClassVersion` in build

### What you see

When running `./dev package`, surefire fails with:

```
com/intellij/openapi/diagnostic/Logger has been compiled by a more
recent version of the Java Runtime (class file version 65.0), this
version of the Java Runtime only recognizes class file versions
up to 61.0
```

### Cause

TeamCity 2026.1 ships its SDK compiled for Java 21 (class version
65). An older `maven:*-eclipse-temurin-17` image cannot load it.

### Fix

Confirm `docker-compose.yml` uses `maven:3.9.9-eclipse-temurin-21`
and that `pom.xml` has:

```xml
<maven.compiler.source>21</maven.compiler.source>
<maven.compiler.target>21</maven.compiler.target>
```

with the Kotlin maven plugin's `<jvmTarget>21</jvmTarget>`.

Then `./dev reset-cache && ./dev package`.

## Symptom: tests fail with `NoClassDefFound: Could not initialize class`

### What you see

```
NoClassDefFound Could not initialize class io.github.dlachouette.teamcity.github.api.GitHubClient
```

### Cause

A test referenced a plugin class whose companion object calls
`Logger.getInstance(...)` during static init, and the IntelliJ
`Logger.Factory` is not bootstrapped.

### Fix

The test class must initialise the test logger bootstrap before
loading any plugin class:

```kotlin
class MyTest {
    init { LoggerBootstrap.install() }
    // ...
}
```

See `src/test/kotlin/.../testsupport/LoggerBootstrap.kt`.

## Symptom: a PR BuildType never shows up and gets no Check Run

### What you see

On a PR, some BuildTypes participate (queued / built / "Skipped")
but others — typically the ones that should run only on ready PRs —
produce no Check Run at all and are never enqueued, even though they
carry the "GitHub Bridge integration" feature.

### Likely causes

| Cause | Fix |
|---|---|
| The feature is **inherited from a BuildType template** and you are on a plugin older than 1.6.0 | Before 1.6.0 the plugin only read features attached *directly* to a BuildType, so a template-only opt-in was invisible. **Upgrade to 1.6.0+** (it reads `resolvedSettings`, which applies templates), or, as a workaround on older versions, re-declare the feature on each BuildType. |
| The feature is disabled on the BuildType (overriding the template) | `Build Features` tab shows it greyed out. Re-enable it. |
| The surrounding project does not provide `teamcity.github.bridge.repo` / `connectionId` | Set them on the project; the slug must match `repository.full_name`. |

### Verify

```bash
grep PullRequestEventListener <TC_DATA_DIR>/logs/teamcity-server.log | tail -20
```

On 1.6.0+ the listener counts template-inherited features. If a
BuildType is still missing, the diagnostic scan (logged when no
candidate is found) reports whether each BuildType carries the
feature and whether its repo matches the event.

## Symptom: a PR Check Run is stuck at "Queued" forever

### What you see

A build never starts — most often because a **snapshot dependency
failed** (TeamCity shows it as "failed to start") — and its GitHub
Check Run stays "Queued" indefinitely instead of turning red.

### Cause

`buildRemovedFromQueue` fires for *every* exit from the queue
(including the build starting), so before 1.6.0 the publisher
returned early on a null user and never drove these rows to a
terminal state.

### Fix

**Upgrade to 1.6.0+.** The publisher now reports a build's own
finished record (failed to start) as **"Build failed"** (red), so a
failed dependency reaches a terminal state and can block the merge.
In a fan-out where every BuildType depends on one build that fails,
that build and all of its dependents end up "Build failed"; duplicate
chain promotions that are torn down without a record are ignored so
they cannot overwrite the real result.

### Verify

```bash
grep BuildStatusCheckRunPublisher <TC_DATA_DIR>/logs/teamcity-server.log | tail -20
```

You should see a `Published queue-removed/finished (failure) Check
Run ...` (or `completed (failure)`) line for the affected build
instead of only a `queued` one.

## Symptom: no "Queued" Check Run — it only appears when the build starts

### What you see

An opted-in PR build produces a Check Run only once it **starts**
(showing "Building"); the earlier **"Queued"** state never shows up on
GitHub, even though the lifecycle is supposed to post queued →
in_progress → completed.

### Cause

`buildTypeAddedToQueue` fires the instant the build is enqueued, but
TeamCity resolves the VCS revision in a background task. At that moment
`promotion.revisions` is often still empty (particularly for builds the
plugin enqueues from a webhook), so the publisher had no head SHA and
skipped the "Queued" Check Run silently. When the build later started,
`buildStarted` ran with the revision resolved and posted "Building" —
hence a row that appears only at start.

### Fix

**Upgrade to the version carrying this fix.** The publisher now retries
the "queued" publish on TeamCity's scheduler (a handful of attempts, a
fraction of a second apart) until the revision resolves. Each retry
aborts if the build has meanwhile started or left the queue, so a late
"Queued" can never overwrite a more advanced `in_progress` / completed
row (GitHub dedups Check Runs by name + head SHA).

### Verify

```bash
grep BuildStatusCheckRunPublisher <TC_DATA_DIR>/logs/teamcity-server.log | tail -20
```

You should see a `Published queued Check Run ...` line shortly after the
build is enqueued. `Deferring queued Check Run ...; revisions not
resolved yet` at debug level shows the retry doing its job.

## Symptom: build container fails on first run

### What you see

```
mkdir: cannot create directory '/root': Permission denied
Can not write to /root/.m2/copy_reference_file.log. Wrong volume permissions? Carrying on ...
```

This is non-fatal. The build proceeds.

### Cause

The Maven image's entrypoint tries to write a log file under
`/root`. With UID mapping (`user: "${USER_UID}:${USER_GID}"`),
`/root` is not writable. We redirect `HOME=/workspace/.cache/home`
and `maven.repo.local=/workspace/.cache/m2` so the real work
happens elsewhere.

### Fix (optional)

If you want the warning to disappear, run the build container with
`HOME=/workspace/.cache/home` already set, which we do in
`docker-compose.yml`. If you still see the warning, ensure
`.cache/home` exists on the host (the `./dev` script creates it).

## Symptom: external API returns 503 or 401

### What you see

```
$ curl -s https://<TC_HOST>/app/teamcity-github-bridge/api/status
{"error":"API disabled (no token configured)"}     # HTTP 503

$ curl -s -H "Authorization: Bearer wrong" .../api/status
{"error":"invalid or missing bearer token"}         # HTTP 401
```

### Likely causes

| Cause | Fix |
|---|---|
| `503` - no API bearer token configured | The external API is disabled until you set an API token. `Administration -> Server Administration -> GitHub Bridge`, set the API token (stored under the `api.token` setting), save. |
| `401` - missing `Authorization` header | Send `Authorization: Bearer <token>`. Any other scheme, or no header, is rejected. |
| `401` - token mismatch | The supplied token differs from the configured one (compared constant-time). Re-copy the exact value; trailing whitespace is trimmed but the bodies must match. |

Distinguish these from TeamCity's own `/app/*` auth 401 (body
`Authentication required`): the API does its own bearer-token auth
via `addPathNotRequiringAuth`, so its 401 body is JSON.

## Symptom: a "/rebuild" comment does nothing

### What you see

A collaborator comments the trigger phrase on a PR, GitHub shows the
`pull_request_review_comment` (or `issue_comment`) delivery as `200`,
but no build is enqueued.

### Likely causes

| Cause | Fix |
|---|---|
| The comment author is not on the allowlist | Only `author_association` values in `comment.allowedAssociations` (default `OWNER,MEMBER,COLLABORATOR`) may trigger. The log shows `Ignoring PR #<n> comment command from <user> (association=<X> not allowed)`. Add the association or grant the user write access. |
| The GitHub App is not sending `pull_request_review_comment` | Enable the **Pull request review comment** event on the App's webhook subscriptions. Without it GitHub never delivers inline-comment triggers. |
| You posted a PR *conversation* comment, not an inline review comment | Conversation comments arrive as `issue_comment`, which GitHub only delivers when the App holds the opt-in **Issues** permission and is subscribed to **Issue comment**. By default the plugin requests neither (it stays scoped to pull requests). Either comment on the PR's diff (an inline review comment) or add the **Issues** permission + `issue_comment` subscription. |
| The trigger phrase does not match | The BuildType's feature must set a non-blank `commentTrigger`, and the phrase must appear in the comment body (case-insensitive substring). Check for typos on either side. |
| The repo is not on the server allowlist | If `repo.allowlist` is set, the repo must be on it; otherwise the listener returns early. |

### Verify

```bash
grep PullRequestEventListener <TC_DATA_DIR>/logs/teamcity-github-bridge.log | tail -20
```

You should see `PR #<n> comment by <user> matched N BT(s)` on a
successful match.

## Symptom: run-on-approval suite never starts after an approval

### What you see

A reviewer approves the PR, but the BuildType meant to run on
approval is never enqueued.

### Likely causes

| Cause | Fix |
|---|---|
| The GitHub App is not sending `pull_request_review` | Enable the **Pull request reviews** event on the App. Without it, approvals never reach the plugin. |
| The BuildType did not opt into run-on-approval | The feature must set run-on-approval and have the PR trigger enabled; the branch must match the trigger's branch filter. |
| The PR is still a draft | `handleReviewApproved` returns early for draft PRs. |
| The review was not an approval | Only `state=approved` submissions are acted on; "commented" or "changes requested" reviews are ignored. |
| The repo is not on the allowlist | Same allowlist gate as the other events. |

### Verify

```bash
grep "approved" <TC_DATA_DIR>/logs/teamcity-github-bridge.log | tail
```

Look for `PR #<n> approved: N run-on-approval BT(s)`.

## Symptom: the GitHub "Re-run" button does nothing

### What you see

Clicking **Re-run** on a TeamCity Check Run in the PR's Checks tab
produces no new build.

### Likely causes

| Cause | Fix |
|---|---|
| The GitHub App is not sending `check_run` | Enable the **Check runs** event on the App's webhook subscriptions. The re-run button fires a `check_run` `rerequested` delivery the plugin must receive. |
| The Check Run name matches no BuildType | The plugin maps `payload.checkRunName` back to a BuildType via `checkRunName(bt)`. If it matches none (e.g. a different CI's check), the log shows `check_run rerequested '<name>' matched no BuildType`. |
| No PR number or head branch in the payload | A `check_run` without an associated PR/branch is logged and ignored. |
| The repo is not on the allowlist | Same allowlist gate. |

Note: re-run intentionally bypasses the "already finished" skip
(`ignoreFinished=true`), but it still skips a build that is
currently running or queued at that head SHA.

## Symptom: the PR summary comment is not posted

### What you see

Builds finish and post Check Runs, but no rolling "TeamCity build
summary" comment appears on the PR thread.

### Likely causes

| Cause | Fix |
|---|---|
| The feature is disabled | The sticky PR comment is **off by default** (`prComment.enabled`). Turn it on in the admin settings. |
| The App lacks pull-requests write | Posting/deleting issue comments needs the App's **Pull requests: Write** (issues write) permission. Without it the upsert logs `Failed upserting PR summary comment ...`. Add the permission and accept it on the App's installation page. |
| Dry-run is on | `maybeUpdatePrComment` is skipped in dry-run. |
| The build is not a PR build | The comment is only posted for builds on a `pull/<n>` branch. |

The comment is a single "sticky" row-per-check summary; because
`HttpURLConnection` cannot PATCH, an update is delete-then-create,
so the comment moves to the bottom of the thread on each refresh.

## Symptom: `/metrics` returns 404

### What you see

```
$ curl -i https://<TC_HOST>/app/teamcity-github-bridge/metrics
HTTP/1.1 404 Not Found
```

### Cause

Metrics export is disabled. `MetricsController` returns `404` when
`metrics.enabled` is off, even though the controller is registered.

### Fix

Enable metrics in the admin settings (`metrics.enabled`). The
endpoint then serves Prometheus text (`bridge_<name>_total`
counters). The same counters are also available as JSON via the
authenticated `/api/metrics` route. Note: 404 here means "disabled",
not "plugin not loaded" - distinguish from a 404 on `/info`, which
points at a load/proxy problem.

## Symptom: builds are not triggered for a particular repo

### What you see

Webhook deliveries arrive `200`, the BuildTypes are correctly
configured, but nothing is enqueued for one specific repo.

### Likely causes

| Cause | Fix |
|---|---|
| The repo allowlist excludes it | If `repo.allowlist` is non-empty, only listed `owner/repo` slugs are acted on; everything else is ignored with `Repo <slug> is not on the allowlist`. Add the repo or clear the allowlist (empty = no restriction). |
| Dry-run is on | Every enqueue becomes a `[dry-run] would enqueue ...` log line with no build added. Turn dry-run off once you have validated the matching. |

### Verify

```bash
grep -E "not on the allowlist|\[dry-run\]" <TC_DATA_DIR>/logs/teamcity-github-bridge.log | tail
```

## Reading the plugin's logs

The plugin uses one logger category per package. Filter by:

```
io.github.dlachouette.teamcity.github.web         -> webhook + info endpoints
io.github.dlachouette.teamcity.github.filter      -> draft-aware filter
io.github.dlachouette.teamcity.github.retrigger   -> retrigger listener
io.github.dlachouette.teamcity.github.api         -> GitHub client + token resolver
io.github.dlachouette.teamcity.github.cache       -> PR info cache
```

Useful log lines:

| Line | Meaning |
|---|---|
| `TeamCity GitHub Bridge plugin loaded` | Bean wired, plugin healthy at startup. |
| `Registered webhook controller at /app/teamcity-github-bridge/webhook` | Endpoint live. |
| `Webhook secret is not configured (...)` | Set `teamcity.github.bridge.webhook.secret` immediately. |
| `Webhook with invalid or missing signature rejected (event=X)` | Signature mismatch; check both sides. |
| `Handling pull_request.<action> for <repo>#<n>` | Webhook received and verified. Action is one of `opened`, `ready_for_review`, `synchronize`. |
| `Skipping <buildType> for <repo>#<n>: already running/queued/finished ...` | Smart-skip kicked in — a build already exists at the same head SHA, no fresh enqueue. |
| `No build types found for <repo>` | None of the active build types are opted in for `<repo>`: either the "GitHub Bridge integration" feature is missing, or the project's `teamcity.github.bridge.repo` / `connectionId` did not resolve. |
| `Suppressing build of <buildType> for draft PR <repo>#<n>` | Draft filter applied. |
| `Cannot resolve token for <buildType>` | The connection ID is wrong or the App is uninstalled. |
| `Cannot fetch PR info for <repo>#<n>` | GitHub API call failed. Check rate limits or permissions. |
| `GitHub returned 4xx for <repo>#<n>` | API rejected. 401 = bad token, 403 = missing permission, 404 = repo not visible. |

## Symptom: PR shows two TeamCity entries (Commit Status + Check Run)

### What you see

In the PR's "All checks" panel, each build appears twice:
- a Commit Status line with description `"TeamCity build finished"`,
- a Check Run line with the actual build status text.

### Cause

The build configuration carries **both** status producers: this plugin's
`BuildStatusCheckRunPublisher` (Check Runs) and TeamCity's bundled
`commitStatusPublisher` (Commit Statuses, with its hard-coded
description). The plugin deliberately does **not** silence the bundled
feature — see [configuration.md](configuration.md#choosing-the-right-setup)
for why that is your call and not the plugin's.

### Fix

**Recommended — disable the bundled publisher** on every build
configuration that has the GitHub Bridge feature:
`Edit Configuration → Build Features → Commit status publisher →
Disable` (or remove it). Confirm on the build's "Build features" tab that
it is off. If the feature comes from a **build configuration template**,
disable it there or override it locally — the bridge reads the *resolved*
feature set, so a template-inherited publisher is just as active.

Tolerated alternative: **leave both** and configure branch protection to
require only the Check Run name (e.g. `TeamCity / <buildType full name>`),
treating the Commit Status as informational. Expect permanent duplicate
rows on every PR.

Not an option: making the plugin disable it for you. It **tells** you — a
`WARN` at server startup and a **Single status publisher** row in the admin
page's self-tests — but correcting the build configuration stays an operator
decision.

## Symptom: admin page shows "No webhook deliveries yet"

### What you see

`Administration -> Server Administration -> GitHub Bridge` reports
`No webhook deliveries yet.` even though you have configured GitHub.

### Likely causes

| Cause | Fix |
|---|---|
| The webhook URL or secret was wrong; GitHub never delivered | Check `Recent Deliveries` on the App's webhook page. If everything there shows 4xx, fix on the GitHub side and re-deliver. |
| TC was restarted recently | The in-memory log is cleared on restart. Trigger a `ping` redeliver from GitHub. |
| The dedicated log file shows entries but the admin page does not | The in-memory log is independent of the file log; only records calls that pass through `PluginWebhookController.doHandle`. If GitHub reaches a reverse proxy and the proxy returns 502 before TC, the plugin never sees the request. Check the proxy access log. |

## Symptom: Check Run on GitHub stays at "Queued" or "In progress"

### What you see

A PR build's Check Run row on GitHub never transitions to a
terminal state — it sits at `Queued` (clock icon) or `In progress`
even after the build was stopped, removed from the queue, or
finished in TC.

### Likely causes

| Cause | Fix |
|---|---|
| Stale plugin version (pre-1.3.0) | Upgrade to 1.3.0+; the lifecycle coverage of `BuildStatusCheckRunPublisher` was extended to `buildInterrupted` and `buildRemovedFromQueue` so a stopped or queue-removed build always transitions to `completed/cancelled`. |
| The build's `head_sha` differs between the in-progress and completed posts | GitHub dedups by `(name, head_sha)`. If a VCS root force-pushed mid-build, the SHAs no longer match; both rows appear. Open the Checks tab and confirm; this is rare. |
| Token expired mid-build | The completed post's `tokenResolver.resolveAccessToken` may return null; check the dedicated log for `Failed publishing completed Check Run`. Self-mint always returns a fresh token, so this usually means the App's permissions changed. |
| Bundled `commitStatusPublisher` overrode our row | Unlikely — Check Runs and Commit Statuses are separate surfaces — but verify by inspecting `Conclusion / Conclusion source` in GitHub's UI. |

## Symptom: draft / ready tags are visible but not styled as pills

### What you see

Tags `draft` and `ready` appear in build lists as plain grey TC tags,
not coloured pills.

### Likely causes

| Cause | Fix |
|---|---|
| The plugin is loaded but `BranchEnrichmentPageExtension` is not registered | Restart TC after upgrading; verify in the server log: `Registered BranchEnrichmentPageExtension at ALL_PAGES_FOOTER_PLUGIN_CONTAINER`. |
| TeamCity's tag markup changed | The CSS selectors in `bridgeBranchEnrichment.jsp` target `.buildTag`, `.tag`, `a.tagLabel`. If a TC update changes these classes, our enrichment silently no-ops. Open an issue. |
| Browser cache | Hard refresh (Ctrl-Shift-R). The fragment is served as part of every page, no separate file to cache, but stale CSS rules on the host page can mask ours. |

## Debug logging

Edit `<TC_DATA_DIR>/config/teamcity-server-log4j.xml`:

```xml
<logger name="io.github.dlachouette.teamcity.github" additivity="false">
    <level value="DEBUG"/>
    <appender-ref ref="ROLL"/>
</logger>
```

Reload via `Administration -> Diagnostics -> Logging` or restart.

Caution: at `DEBUG`, the plugin logs every cache hit and miss. The
log volume is significant on a busy server.

## When all else fails

Open an issue with:
- TeamCity version (`Administration -> Diagnostics`).
- Plugin version (in the plugin's archive name).
- A redacted excerpt of the relevant log lines (search for
  the package prefix `io.github.dlachouette` and copy 20 lines of
  context).
- The output of `curl /info` (it does not contain the secret, only
  whether one is configured).
- If applicable, a redacted recent webhook delivery body from
  GitHub's UI.

Never include the App private key, installation tokens, or the
webhook secret in an issue.
