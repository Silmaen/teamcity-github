# teamcity-github-bridge

Server-side TeamCity plugin that fills the gaps in the bundled GitHub
integration (TeamCity 2026.1+):

- detects PR draft state via the GitHub REST API
- retriggers eligible builds when a PR transitions from draft to ready for review
- ships a `StartBuildPrecondition` that honors a per-buildType "ignore drafts" opt-in
- exposes a single App-level webhook endpoint with HMAC-SHA256 verification
- surfaces the live webhook config at `/info` for copy-paste into GitHub

**See [doc/usage.md](doc/usage.md) for the full operator guide
(install, GitHub App setup, webhook config, troubleshooting, with
mermaid diagrams).**

The design rationale and the TeamCity 2026.1 internals behind it live
in `doc/teamcity-plugin-knowledge-base.md` (French, transfer
document).

## Build

The whole toolchain (JDK 21 + Maven 3.9) runs in Docker. The only
requirement on the host is a working `docker` (with the Compose plugin).
TeamCity 2026.1 ships its API compiled for Java 21, so the matching
JDK is required at compile and test time.

```bash
./dev package
```

This invokes `mvn clean package` inside the `maven:3.9.9-eclipse-temurin-21`
image and produces `target/teamcity-github-bridge-<version>.zip`.

Other commands:

```bash
./dev compile          # mvn compile
./dev test             # mvn test
./dev mvn <args>       # arbitrary mvn pass-through
./dev shell            # interactive bash in the container
./dev reset-cache      # nuke .cache/m2/
./dev help             # full usage
```

The Maven local repository lives in `.cache/m2/` (project-scoped,
gitignored) so nothing pollutes the host.

## Install

Copy the produced zip to your TeamCity Data Dir:

```bash
cp target/teamcity-github-bridge-*.zip <TC_DATA_DIR>/plugins/
```

Then restart the TeamCity server (or hot-load via
`Administration -> Plugins List -> Upload Plugin Zip`).

## Configure per-buildType (opt-in)

Add these parameters on the build configuration (or its template):

| Parameter | Purpose |
|---|---|
| `tcgh.ignoreDrafts` | `true` to skip builds for draft PRs |
| `tcgh.github.repo` | `owner/name` slug of the GitHub repository |
| `tcgh.github.connectionId` | TeamCity GitHub App connection ID used for API auth |

## Communication model

All TeamCity to GitHub traffic goes through the **GitHub App** (no PATs,
no OAuth App user tokens):

- **Outbound (TC -> GitHub)**: REST calls authenticated with an
  installation token resolved from a TeamCity GitHub App connection
  via `OAuthTokensStorage`. The plugin never stores raw credentials.
- **Inbound (GitHub -> TC)**: a single App-level webhook posts events to
  this plugin's endpoint. The plugin verifies the HMAC SHA-256
  signature (header `X-Hub-Signature-256`) before acting on any payload.

## App-level webhook setup

1. **Configure the secret on the TeamCity side first**

   In `<TC_DATA_DIR>/config/internal.properties` add:

   ```
   tcgh.webhook.secret=<a-long-random-string>
   ```

   then restart the server (or trigger a config reload).

2. **Inspect the live webhook config**

   Once the plugin is installed, fetch the auto-generated configuration:

   ```bash
   curl https://<TC_URL>/app/teamcity-github-bridge/info        # JSON
   curl https://<TC_URL>/app/teamcity-github-bridge/info.md     # Markdown table
   ```

   The response includes the absolute `payloadUrl`, the expected content
   type, the list of recommended events, and whether the secret is
   configured.

3. **Paste the values into the GitHub App**

   In `https://github.com/settings/apps/<your-app>` -> **Webhook**:

   | Field | Value |
   |---|---|
   | Payload URL | `https://<TC_URL>/app/teamcity-github-bridge/webhook` |
   | Content type | `application/json` |
   | Secret | the same string you set in `tcgh.webhook.secret` |
   | SSL verification | Enable |

   Then in **Subscribe to events**, enable at least: `pull_request`,
   `pull_request_review`, `push`, `check_suite`.

The plugin will reject any request without a valid signature with HTTP
401, so installing the webhook without setting the secret intentionally
fails closed.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
