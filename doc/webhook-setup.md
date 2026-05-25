# Webhook setup

Once a [GitHub App](github-app-setup.md) is wired to TeamCity, the
remaining piece is making GitHub deliver events back to the plugin.

This plugin uses a **single App-level webhook** per GitHub App. No
per-repo webhooks to maintain.

## How it differs from `teamcity-commit-hooks`

```mermaid
flowchart TD
    classDef old fill:#ffebee,stroke:#c62828
    classDef new fill:#e8f5e9,stroke:#2e7d32

    subgraph CommitHooks["teamcity-commit-hooks (bundled)"]
        direction LR
        R1[Repo A] -->|"webhook/&lt;UUID-A&gt;"| TC1[TeamCity]
        R2[Repo B] -->|"webhook/&lt;UUID-B&gt;"| TC1
        R3[Repo C] -->|"webhook/&lt;UUID-C&gt;"| TC1
    end
    class CommitHooks old

    subgraph Bridge["teamcity-github-bridge (this plugin)"]
        direction LR
        APP[GitHub App] -->|"app/teamcity-github-bridge/webhook"| TC2[TeamCity]
        R4[Repo A] -.installed.- APP
        R5[Repo B] -.installed.- APP
        R6[Repo C] -.installed.- APP
    end
    class Bridge new
```

You can keep `commit-hooks` for repos that need it; the two plugins
do not conflict.

## Configure the webhook in three steps

```mermaid
sequenceDiagram
    actor Admin
    participant TC as TeamCity
    participant GH as GitHub App settings

    Admin->>TC: 1. set teamcity.github.bridge.webhook.secret<br/>in internal.properties
    Admin->>TC: 2. GET /app/teamcity-github-bridge/info
    TC-->>Admin: payloadUrl, recommendedEvents,<br/>contentType, secretConfigured: true
    Admin->>GH: 3. paste payloadUrl + secret<br/>+ tick events
    GH->>TC: ping (signed)
    TC-->>GH: 200 pong
```

### Step 1: configure the HMAC secret on the TeamCity side

Generate a strong random string (>=32 bytes):

```bash
openssl rand -hex 48
# 0a4f0c9b5e8e3c1d2a4b...
```

Three ways to install it, in order of preference:

**Via the plugin's admin page (recommended since v0.6.0)**

Open `Administration -> Server Administration -> GitHub Bridge`.
Under `HMAC secret` paste the random string into the form and
click **Save**. The plugin writes the value to
`<TC_DATA_DIR>/config/teamcity-github-bridge.properties` and the
next webhook delivery uses the new secret immediately.

You can also **Clear secret** from the same form, which removes the
key and re-enables fail-closed mode (every delivery rejected with
401 until a new secret is set).

**Via the TC internal-properties UI (legacy, still supported)**

Go to `Administration -> Server Administration -> Diagnostics ->
Internal Properties` (direct URL:
`https://<TC>/admin/admin.html?item=diagnostics&tab=internalProperties`)
and add:

```properties
teamcity.github.bridge.webhook.secret=<paste the string here>
```

If both sources are populated, the plugin's own file takes
precedence (visible in the admin page as "via this page" vs "via
internal.properties - legacy").

**Via the filesystem**

Either edit `<TC_DATA_DIR>/config/teamcity-github-bridge.properties`
directly:

```properties
webhook.secret=<paste the string here>
```

or `<TC_DATA_DIR>/config/internal.properties`:

```properties
teamcity.github.bridge.webhook.secret=<paste the string here>
```

Both are hot-reloaded; no restart needed.

> **Important**: do **not** confuse this secret with the "Webhook
> secret" field inside the GitHub App connection form (`Project ->
> Connections -> Edit GitHub App -> Webhook secret`). That field is
> stored on the connection descriptor and is consumed by TeamCity's
> **bundled** integrations only; this plugin does not read it.

> The plugin **rejects any request without a valid signature** with
> HTTP 401. Until this secret is set, GitHub will see 401 on every
> delivery - that's intentional, fail-closed.

### Step 2: fetch the live config from the plugin

```bash
curl -s https://<TC_HOST>/app/teamcity-github-bridge/info | jq
```

Example output:

```json
{
  "payloadUrl": "https://teamcity.example.com/app/teamcity-github-bridge/webhook",
  "contentType": "application/json",
  "sslVerification": true,
  "recommendedEvents": ["pull_request", "pull_request_review", "push", "check_suite", "ping"],
  "secretConfigured": true,
  "pluginVersion": "TeamCity 2026.1 (build 222521)"
}
```

A Markdown rendering ready to paste into a wiki/runbook is at:

```bash
curl -s https://<TC_HOST>/app/teamcity-github-bridge/info.md
```

### Step 3: configure GitHub

Open your App at `https://github.com/settings/apps/<your-app>` and
scroll to **Webhook**.

| GitHub field | Value | Source |
|---|---|---|
| Active | Checked | n/a |
| Webhook URL | `payloadUrl` from `/info` | step 2 |
| Content type | `application/json` | required (the plugin parses JSON, not URL-encoded form data) |
| Secret | the random string from step 1 | step 1 |
| SSL verification | Enable | required - the plugin assumes TLS |

```
GitHub App > Webhook
+------------------------------------------------+
| [x] Active                                     |
|                                                |
| Webhook URL                                    |
| [ https://teamcity.example.com/app/teamcity-]  |
| [ github-bridge/webhook                      ] |
|                                                |
| Content type                                   |
| [ application/json                v]           |
|                                                |
| Secret                                         |
| [ ************************************      ] |
|                                                |
| SSL verification                               |
| (o) Enable SSL verification                    |
| ( ) Disable                                    |
+------------------------------------------------+
```

Then scroll to **Subscribe to events** and tick at least:

- [x] **Pull request** - required for draft/ready-for-review detection.

Optional (forward-compatible, the plugin will use them in future
versions):

- [x] Pull request review
- [x] Push
- [x] Check suite

Click `Save changes`.

## Verify the wiring

GitHub sends a `ping` event right after you save. You can see it in
`Advanced -> Recent Deliveries`:

```
Recent Deliveries
+----------------------------------------------------------+
|  v  ping            12:34:56  200  Response: pong        |
+----------------------------------------------------------+
```

If you see `200 pong`, the round-trip is complete.

If you see `401 Invalid signature`:
- The secret on GitHub and the one in `teamcity.github.bridge.webhook.secret` differ.
- Or a reverse proxy is rewriting the request body. Check your
  ingress config; the plugin signs over the raw body bytes.

If you see `404 Not Found`:
- The plugin is not loaded. Re-check the server log
  ([installation.md#step-3-verify-the-load](installation.md#step-3-verify-the-load)).
- Or the URL is wrong - it must be exactly `/app/teamcity-github-bridge/webhook`.

## What the plugin does with each event

| Event | Action | Acknowledged with |
|---|---|---|
| `ping` | Nothing; health check. | `200 pong` |
| `pull_request` with `action: ready_for_review` | Look up matching build configs, enqueue a build for `pull/N` on each. | `200 OK` |
| `pull_request` (other actions) | Ignored. | `200 OK` |
| Any other event | Ignored. | `204 No Content` |

See [usage-scenarios.md](usage-scenarios.md) for end-to-end flows.

## Rotating the secret

Eventually you'll want to rotate. The plugin does not support
overlapping secrets, so coordinate the rotation:

1. Generate a new secret.
2. Update `teamcity.github.bridge.webhook.secret` in TeamCity's `internal.properties`.
   TeamCity hot-reloads the file - the new secret is live within a
   second.
3. Update the secret on the GitHub App webhook page and save.
4. There is a small window (sub-second) where one delivery may be
   rejected. GitHub auto-retries failed deliveries, so this is
   non-fatal but worth mentioning.

## What's next

- Continue with [configuration.md](configuration.md) to enable the
  bridge on a build type.
- Or read [usage-scenarios.md](usage-scenarios.md) to understand
  what triggers what.
