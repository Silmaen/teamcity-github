# Troubleshooting

A reference for diagnosing common issues. Symptoms first, causes
second, fix third.

## Where to look first

```
+---------------------------------------------------------------+
|  1. Self-test button (v0.9.0+)  *** start here ***            |
|     Admin -> Server Admin -> GitHub Bridge -> Run self-tests  |
|     -> PASS/WARN/FAIL/SKIP table localises the broken step    |
+---------------------------------------------------------------+
|  2. Plugin /info endpoint (one-shot health snapshot)          |
|     curl https://<TC_HOST>/app/teamcity-github-bridge/info    |
|     -> secretConfigured, logConfigured, payloadUrl, logFile   |
+---------------------------------------------------------------+
|  3. Dedicated plugin log                                      |
|     <TC_DATA_DIR>/logs/teamcity-github-bridge.log             |
|     -> auto-configured at startup since v0.6.0                |
+---------------------------------------------------------------+
|  4. Server log fallback (if dedicated log was overridden)     |
|     <TC_DATA_DIR>/logs/teamcity-server.log                    |
|     Grep for `io.github.dlachouette` (package) or             |
|     `teamcity-github-bridge` (plugin name).                   |
+---------------------------------------------------------------+
|  5. GitHub App webhook "Recent Deliveries" panel              |
|     https://github.com/settings/apps/<your-app>/advanced      |
+---------------------------------------------------------------+
|  6. Queue UI                                                  |
|     Look at the wait reason on held builds                    |
+---------------------------------------------------------------+
```

## Symptom: self-test shows "Token resolution" FAIL on every project

### What you see

After clicking **Run self-tests** the rows
`Token resolution / <project> / <repo>` are all FAIL with detail
`TC could not produce an installation token.`

### Likely causes (in order of frequency on a vanilla TC 2026.1)

| Cause | Fix |
|---|---|
| The App is not installed on the target repository's owner (org or user account) | Visit `https://github.com/settings/apps/<your-app>/installations` and install the App on the owner of the repo named in `teamcity.github.bridge.repo`. |
| The App's permissions do not cover the repo (e.g. `Checks: Write` missing) | Add at minimum `Pull requests: Read`, `Checks: Write`, `Contents: Read`, `Metadata: Read`; accept the permission update on the App's installation page. |
| The connection's `appId` or `secure:privateKey` parameter is missing or empty (manual edit of the project file?) | Open `Project -> Connections -> Edit`, paste the App ID and private key, save. The self-mint path needs both. The plugin logs `Connection PROJECT_EXT_N does not expose the GitHub App credentials this plugin needs` when this happens. |
| The private key cannot be parsed (truncated, wrong format, mangled by a copy-paste) | Re-paste the `.pem` file content as-is. The plugin accepts both `-----BEGIN PRIVATE KEY-----` (PKCS#8) and `-----BEGIN RSA PRIVATE KEY-----` (PKCS#1). The log entry is `Could not parse the private key stored on connection PROJECT_EXT_N`. |
| The `teamcity.github.bridge.connectionId` value points at a project the connection is not visible from | Confirm in TC: `Project -> Connections` should list the connection on the project's own page or on one of its parents. |

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
TeamCity, not a held one with a wait reason.

### Likely causes

| Cause | Fix |
|---|---|
| Build type does not have `teamcity.github.bridge.ignoreDrafts=true` | Add it. See [configuration.md](configuration.md). |
| Build type does not have `teamcity.github.bridge.repo` | Add it; the slug must match `repository.full_name` from GitHub. |
| `teamcity.github.bridge.connectionId` points to a wrong/non-existent connection | The plugin logs `No GitHub App connection found for id=...`. Fix the ID. |
| GitHub App lacks `Pull requests: read` permission | API returns 403; plugin logs the warning and fails open. Add the permission and accept it on the App page. |
| Branch does not look like `pull/N` | The filter only acts on branch names matching `pull/<number>`. Check the VCS root's branchSpec. |
| Build was triggered manually by an operator (since v1.3.0) | This is intentional: a manual "Run" bypasses the draft gate. The log shows `Allowing manual user trigger of <buildType> on draft PR`. To suppress this, ask the operator to wait until the PR is marked ready. |

### Verify

```bash
# Watch the live filter behaviour
tail -f <TC_DATA_DIR>/logs/teamcity-server.log | grep DraftAwareBuildFilter
```

You should see one of:

- `Suppressing build of <buildType> for draft PR <repo>#<n>` (works)
- `Cannot resolve token for <buildType>; allowing build to proceed`
- `Cannot fetch PR info for <repo>#<n>; allowing build to proceed`

If neither appears, the filter is not even reaching this build:
check the three parameters and the branch name.

## Symptom: PR marked ready, but no retrigger happens

### What you see

A PR transitioned from draft to ready in GitHub, the `Recent
Deliveries` panel shows a `200 OK`, but no new builds appear in the
queue.

### Likely causes

| Cause | Fix |
|---|---|
| `teamcity.github.bridge.repo` slug does not match GitHub's `repository.full_name` | Compare case-sensitively. GitHub normalises owner/name casing differently in some places. |
| Build types do not have `teamcity.github.bridge.ignoreDrafts=true` | The retrigger filter requires both `teamcity.github.bridge.repo` match and `teamcity.github.bridge.ignoreDrafts="true"`. |
| Build queue optimiser deduped against an existing build | A build for `pull/N` on the same revision may already be running. Check the running builds list. |
| Build types are paused | `ProjectManager.activeBuildTypes` excludes paused. Unpause or use a sibling build type. |

### Verify

```bash
grep ReadyForReviewListener <TC_DATA_DIR>/logs/teamcity-server.log | tail -20
```

You should see, on each event:

```
INFO  - Handling ready_for_review for <repo>#<n>
INFO  - Retriggering N build type(s) for <repo>#<n>
```

If `N=0`, the filter found no matching configurations; verify the
parameters.

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
| `Handling ready_for_review for <repo>#<n>` | Webhook received and verified. |
| `No build types found for <repo>` | None of the active build types have `teamcity.github.bridge.repo=<repo>` + `teamcity.github.bridge.ignoreDrafts=true`. |
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

This is **expected** on opted-in build types as of v0.4.0. The plugin's
`BuildStatusCheckRunPublisher` posts Check Runs but does **not** silence
the bundled `commitStatusPublisher`, which keeps posting Commit
Statuses with its hard-coded description.

### Fix

Two options:
- **Leave both**, configure branch protection to require only the
  Check Run name (e.g. `TeamCity / <buildType full name>`); treat the
  Commit Status as informational.
- **Disable `commitStatusPublisher`** on the opted-in build types via
  the bundled feature's UI (`Edit Configuration -> Build Features ->
  Commit status publisher -> Disable`). Confirm via the build's
  "Build features" tab that the publisher is off.

A future plugin iteration will provide a Build Feature to suppress the
bundled publisher per-buildType automatically.

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
