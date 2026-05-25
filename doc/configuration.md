# Configuration reference

Every knob the plugin exposes, in one page.

## Configuration surfaces

```
+-----------------------------------------------------------+
| 1. Plugin-level defaults                                  |
|    -> teamcity-plugin.xml (shipped, edit only to rebuild) |
+-----------------------------------------------------------+
| 2. Server-wide internal properties                        |
|    -> <TC_DATA_DIR>/config/internal.properties            |
|    -> hot-reloaded, no restart needed                     |
+-----------------------------------------------------------+
| 3. Per-buildType parameters                               |
|    -> BuildType -> Parameters                             |
|    -> instant effect                                      |
+-----------------------------------------------------------+
```

## 1. Plugin-level defaults (shipped)

Declared in `src/main/resources/teamcity-plugin.xml`. You override
them by setting an internal property with the same name (option 2
below) - the plugin reads the property first, then falls back to
this default.

| Parameter | Default | Purpose |
|---|---|---|
| `tcgh.github.api.base` | `https://api.github.com` | Override for GitHub Enterprise. |
| `tcgh.github.api.version` | `2022-11-28` | The `X-GitHub-Api-Version` header value sent on REST calls. |
| `tcgh.prinfo.cache.ttl.seconds` | `60` | TTL for the in-memory PR info cache. |
| `tcgh.webhook.path` | `/app/teamcity-github-bridge/webhook` | The endpoint path. Changing this also affects what `/info` returns. |

## 2. Server-wide internal properties

Set in `<TC_DATA_DIR>/config/internal.properties`. Hot-reloaded.

| Property | Default | Required | Purpose |
|---|---|---|---|
| `tcgh.webhook.secret` | _unset_ | **yes** (or set via the admin page) | HMAC secret used to verify webhook signatures. Without this, every request is rejected with 401. Since v0.6.0 you can also set this from the admin page; the plugin's own file (`teamcity-github-bridge.properties`) takes precedence over this key. |
| `tcgh.github.api.base` | (plugin default) | no | Override for GitHub Enterprise; e.g. `https://github.acme.com/api/v3`. |
| `tcgh.prinfo.cache.ttl.seconds` | `60` | no | Increase if you hit GitHub rate limits; decrease for tighter loops. |

### Plugin-owned settings file (v0.6.0+)

`<TC_DATA_DIR>/config/teamcity-github-bridge.properties` holds
values that the admin page writes. Currently a single key:

| Key | Purpose |
|---|---|
| `webhook.secret` | Same role as `tcgh.webhook.secret` above, but stored separately so the plugin never has to mutate `internal.properties`. Wins over `tcgh.webhook.secret` if both are set. |

You can edit this file by hand if you prefer, but the admin page is
the supported path.

Example `internal.properties` entry:

```properties
# tcgh-bridge: HMAC secret for the App-level webhook
tcgh.webhook.secret=0a4f0c9b5e8e3c1d2a4b6c8d9e0f1a2b3c4d5e6f7a8b9c0d
```

## 3. Per-buildType parameters

The bridge is opt-in: a build configuration is affected only if it
declares all three parameters. This avoids touching unrelated build
types.

| Parameter | Required | Example | Purpose |
|---|---|---|---|
| `tcgh.ignoreDrafts` | yes | `true` | Setting to `"true"` enables draft suppression and inclusion in the ready-for-review retrigger. |
| `tcgh.github.repo` | yes | `Silmaen/Owl` | The `owner/name` slug as GitHub reports it in `repository.full_name`. |
| `tcgh.github.connectionId` | yes | `PROJECT_EXT_42` | The TeamCity GitHub App connection ID resolved by `OAuthConnectionsManager`. Visible in the URL of the project's Connections page. |

### How to set them

#### Via the UI

`Edit Configuration -> Parameters -> Add new parameter`. Pick
`Configuration parameter` (not env var, not system property).

#### Via a template (recommended for many build types)

Put them on a parent template and the children inherit:

```
+------------------------------------------------+
| Template: GitHubAwarePR                        |
|   parameters:                                  |
|     tcgh.ignoreDrafts        = true            |
|     tcgh.github.repo         = Silmaen/Owl     |
|     tcgh.github.connectionId = PROJECT_EXT_42  |
+------------------------------------------------+
          |
          v inherits
+------------------------------------------------+
| BuildType: Build_LinuxX64_Clang                |
| BuildType: Build_LinuxX64_Gcc                  |
| BuildType: Build_WindowsX64_Clang              |
+------------------------------------------------+
```

#### Via Kotlin DSL

```kotlin
object GitHubAwarePR : Template({
    id("GitHubAwarePR")
    name = "GitHub-aware PR template"
    params {
        param("tcgh.ignoreDrafts", "true")
        param("tcgh.github.repo", "Silmaen/Owl")
        param("tcgh.github.connectionId", "PROJECT_EXT_42")
    }
})
```

### Enable on a build configuration

1. Pick a build configuration that runs on PR refs (`+:refs/pull/*/head`
   or similar in its VCS root branchSpec).
2. Add the three parameters above.
3. Save. The next queued build hits the plugin's
   `StartBuildPrecondition`; if the PR is draft, the build is held
   with a wait reason `"PR #N is draft and tcgh.ignoreDrafts is enabled"`.

There is **no UI badge** indicating the plugin is active on a build
type. The presence of the three parameters is the sole signal.
Future versions may add a Build Feature for clearer UX; tracked in
[development.md#roadmap](development.md#roadmap).

## Parameter precedence

```mermaid
flowchart TD
    A[tcgh.webhook.secret read by WebhookConfig] --> B{internal.properties<br/>has the key?}
    B -->|yes| C[Use that value]
    B -->|no, blank| D[Return null<br/>-> reject every webhook]

    E[tcgh.github.api.base etc] --> F{Plugin-level<br/>parameter set?}
    F -->|yes via TC API| G[Use that value]
    F -->|no| H[Use shipped default<br/>from teamcity-plugin.xml]
```

Notes:
- Internal properties win over plugin-level parameters when both
  are set.
- Per-buildType parameters do not override server-wide settings; they
  configure which builds participate.

## Logging tuning

The plugin uses IntelliJ openapi `Logger` which delegates to log4j
when running inside TeamCity.

### Dedicated log file

As of v0.6.0 the plugin **attaches its own log appender at startup**.
No manual setup needed:

- File: `<TC_DATA_DIR>/logs/teamcity-github-bridge.log` (same dir
  as `teamcity-server.log`).
- Rotation: size-based, 10 MB per file, 10 historical files (~100 MB
  retention).
- Pattern: `[<ISO-8601 timestamp>] <LEVEL> <ClassName> - <message>`.
- Routing: all `io.github.dlachouette.teamcity.github.*` log entries
  go to this file with `additivity="false"`, i.e. they no longer
  duplicate to `teamcity-server.log`.

The admin page reports the state under `Dedicated log`:
- **auto-configured** - the plugin attached the appender at startup
  (the common case).
- **operator-configured** - an existing appender on the package
  logger was detected; the plugin left it alone.
- **not configured** - rare, only when the appender attachment threw.
  See troubleshooting.

### Manual override (advanced)

If you prefer to take control of the routing - different rotation,
remote syslog, etc. - configure your own appender for the
`io.github.dlachouette.teamcity.github` logger in
`<TC_DATA_DIR>/config/teamcity-server-log4j.xml`. The plugin
detects an existing appender and skips its own attachment.

The shipped reference snippet
(`/teamcity-github-bridge-log4j-snippet.xml` inside the plugin jar -
also available at `src/main/resources/teamcity-github-bridge-log4j-snippet.xml`
in the repository) can be merged into `<TC_DATA_DIR>/config/teamcity-server-log4j.xml`,
inside the root `<log4j:configuration>` element:

```xml
<appender name="TCGH_BRIDGE" class="org.apache.log4j.rolling.RollingFileAppender">
    <rollingPolicy class="jetbrains.buildServer.util.TCRollingPolicy">
        <param name="FileNamePattern"
               value="${teamcity_logs}/teamcity-github-bridge.log.%d{yyyy-MM-dd}.gz"/>
        <param name="ActiveFileName" value="${teamcity_logs}/teamcity-github-bridge.log"/>
        <param name="MaxHistory" value="14"/>
    </rollingPolicy>
    <layout class="org.apache.log4j.PatternLayout">
        <param name="ConversionPattern" value="[%d{HH:mm:ss.SSS}] %-5p %c{1} - %m%n"/>
    </layout>
</appender>

<logger name="io.github.dlachouette.teamcity.github" additivity="false">
    <level value="INFO"/>
    <appender-ref ref="TCGH_BRIDGE"/>
</logger>
```

TeamCity hot-reloads log4j on file change; no restart needed.

Result: plugin entries land in `<TC_DATA_DIR>/logs/teamcity-github-bridge.log`,
rolled daily, gzipped, 14 days retention. The server-wide
`teamcity-server.log` no longer contains
`io.github.dlachouette.*` entries.

Verify by curling `/info`:

```bash
curl -s https://<TC_HOST>/app/teamcity-github-bridge/info | jq '.logConfigured, .logFile'
# true
# "/data/teamcity_server/datadir/logs/teamcity-github-bridge.log"
```

### Quick debug logging without a dedicated file

If you only want debug temporarily and do not care about routing:

```xml
<!-- <TC_DATA_DIR>/config/teamcity-server-log4j.xml -->
<logger name="io.github.dlachouette.teamcity.github" additivity="false">
    <level value="DEBUG"/>
    <appender-ref ref="ROLL"/>
</logger>
```

Reload via `Administration -> Diagnostics -> Logging` or restart.

## Check Run publisher coexistence with the bundled `commitStatusPublisher`

As of v0.4.0, the plugin's `BuildStatusCheckRunPublisher` posts
GitHub Check Runs on every lifecycle event for an opted-in build
type. The bundled `commitStatusPublisher` keeps posting Commit
Statuses (with the hard-coded `"TeamCity build finished"`
description) unless you disable it.

### What the plugin emits

| Event | Check Run `status` | Check Run `conclusion` | `output.title` |
|---|---|---|---|
| Held in draft queue | `completed` | `skipped` | `Skipped: draft PR` |
| Build starts | `in_progress` | (none) | `Building` |
| Build finishes (success / warning) | `completed` | `success` | `Build passed` |
| Build finishes (failure / error) | `completed` | `failure` | `Build failed` |
| Build cancelled | `completed` | `cancelled` | `Build cancelled` |
| Build status `UNKNOWN` | `completed` | `neutral` | `Build status: ...` |

`output.summary` carries the build's `statusDescriptor.text` (i.e.
whatever the agent set via
`##teamcity[buildStatus text='...']`). Truncated at 60 000
characters with a `(truncated)` marker if longer.

### Choosing the right setup

| Goal | Action |
|---|---|
| Keep both publishers (informational fallback) | Do nothing. Update branch protection to require **only** Check Run names like `TeamCity / <buildType full name>`. Commit Statuses appear but are not blocking. |
| Single source of truth | Disable the bundled `commitStatusPublisher` on the opted-in build types via the existing UI (Build Features tab). The plugin's Check Runs become the only TC signal. |

The "disable per-buildType" path is currently manual; a future
release will provide a Build Feature for one-click opt-out.

## Validation

The plugin does not actively validate the parameters at save time
(no UI hook yet). What it does:

- **`tcgh.github.repo` malformed** -> `RepoCoords.parse` throws
  `IllegalArgumentException`, caught and logged. The build is
  allowed to proceed (fail-open).
- **`tcgh.github.connectionId` not found** -> `TokenResolver`
  returns null, logged as warning. Build proceeds (fail-open).
- **`tcgh.ignoreDrafts` value other than `"true"`** -> treated as
  disabled. No error.

To validate without queuing a build, watch the server log while you
manually trigger a `ping` from the GitHub App settings.

## Next

- See it in action: [usage-scenarios.md](usage-scenarios.md).
- What to do when something looks wrong: [troubleshooting.md](troubleshooting.md).
