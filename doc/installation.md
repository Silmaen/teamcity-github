# Installation

This page covers the mechanical install. For wiring the plugin to a
GitHub App, continue with [github-app-setup.md](github-app-setup.md).

## Prerequisites

| Requirement | Version | Notes |
|---|---|---|
| TeamCity Server | 2026.1 (build 222521 or newer) | Older versions are not supported - the plugin uses APIs that landed in 2026.1. |
| Docker on the build host | recent (with Compose plugin) | The plugin is built entirely inside an `eclipse-temurin-21` container; you do **not** need a JDK on the host. |
| Network access | github.com + api.github.com | Required at runtime for token refresh and PR info queries. |

## Where the plugin lives

```
 +-------------------------------+
 |  TeamCity Data Directory      |
 |  <TC_DATA_DIR>/               |
 |  |- plugins/                  |
 |  |    teamcity-github-bridge- |
 |  |    0.5.0.zip               |  <-- drop the archive here
 |  |- config/                   |
 |  |    internal.properties     |  <-- tcgh.webhook.secret goes here
 |  |    teamcity-server-log4j.xml   <-- optional log tuning
 +-------------------------------+
```

## Step 1: build the archive

```bash
git clone https://github.com/silmaen/teamcity-github.git
cd teamcity-github
./dev package
```

This produces `target/teamcity-github-bridge-<version>.zip`. The
archive layout matches what TeamCity expects:

```
teamcity-github-bridge-<version>.zip
|- teamcity-plugin.xml             # plugin descriptor at the root
|- server/
   |- teamcity-github-bridge-<version>.jar  # the plugin code
   |- kotlin-stdlib-1.9.25.jar              # bundled (separate classloader)
   |- kotlin-reflect-1.7.22.jar             #
   |- jackson-core-2.17.2.jar               #
   |- jackson-databind-2.17.2.jar           #
   |- jackson-annotations-2.17.2.jar        #
   |- jackson-module-kotlin-2.17.2.jar      #
   |- annotations-13.0.jar                  #
```

> **Note**: the plugin declares `use-separate-classloader="true"` in
> its descriptor, so the Kotlin runtime and Jackson are bundled and
> isolated from TeamCity's own classpath.

## Step 2: deploy

Two options.

### Option A: drop and restart (recommended for first install)

```bash
cp target/teamcity-github-bridge-*.zip /path/to/tc-data-dir/plugins/
sudo systemctl restart teamcity-server   # or however you restart yours
```

### Option B: hot upload (faster for iteration)

1. Log into TeamCity as a system administrator.
2. Go to `Administration -> Plugins List`.
3. Click `Upload plugin zip` and pick the archive.
4. Go to `Administration -> Diagnostics -> Server Health` and click
   `Show all health items` - you'll see a prompt to restart the
   server to load the new plugin.

> Some teams run TeamCity in Docker. In that case, mount the data
> directory and copy the archive into `/data/teamcity_server/datadir/plugins/`
> from outside the container.

## Step 3: verify the load

After restart, check the server log:

```bash
tail -f <TC_DATA_DIR>/logs/teamcity-server.log
```

You should see:

```
[main] INFO  - i.g.d.t.g.TeamCityGitHubBridgePlugin - TeamCity GitHub Bridge plugin loaded (build server: TeamCity 2026.1 (build 222521))
[main] INFO  - i.g.d.t.g.w.PluginWebhookController - Registered webhook controller at /app/teamcity-github-bridge/webhook
```

Then verify the HTTP endpoints respond:

```bash
curl -sI https://<TC_HOST>/app/teamcity-github-bridge/info
# HTTP/1.1 200 OK
# Content-Type: application/json; charset=UTF-8

curl -s https://<TC_HOST>/app/teamcity-github-bridge/info | jq
```

You should get a JSON snapshot of the live config. At this stage the
field `secretConfigured` is `false` - that's expected; you'll fix it
in [webhook-setup.md](webhook-setup.md).

## Step 4: confirm the UI

Go to `Administration -> Plugins List`. You should see:

```
+----------------------------------------------------+
| TeamCity GitHub Bridge                  v0.5.0     |
|  by silmaen                             enabled  ON|
|  Min API: 222521                                   |
+----------------------------------------------------+
```

If the plugin is greyed out or the version is missing, refer to
[troubleshooting.md](troubleshooting.md#plugin-does-not-load).

## Uninstall

```bash
rm <TC_DATA_DIR>/plugins/teamcity-github-bridge-*.zip
```

Then restart TeamCity (or remove via the UI's `Plugins List ->
Disable / Delete`). No external state is created; per-buildType
parameters remain on the build configurations and can be cleaned up
separately if desired.

## Next steps

- **First-time setup**: continue with [GitHub App setup](github-app-setup.md).
- **Already have a GitHub App connection**: go to [Webhook setup](webhook-setup.md).
- **Just want to enable on more build types**: jump to
  [Configuration -> Enable on a build configuration](configuration.md#enable-on-a-build-configuration).
