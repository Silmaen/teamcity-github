# HTTP API reference

The three HTTP endpoints the plugin registers in TeamCity, with
request/response shapes and curl examples.

All endpoints are mounted under `/app/teamcity-github-bridge/`.
None of them require TeamCity authentication; the webhook is
protected by HMAC and the info endpoints expose nothing sensitive.

## Endpoint summary

| Method | Path | Purpose | Auth |
|---|---|---|---|
| `POST` | `/app/teamcity-github-bridge/webhook` | Receive GitHub webhooks | HMAC-SHA256 over the body |
| `GET`  | `/app/teamcity-github-bridge/info` | Live webhook config snapshot (JSON) | none |
| `GET`  | `/app/teamcity-github-bridge/info.md` | Same snapshot as Markdown | none |
| `GET`  | `/admin/admin.html?tab=bridgeAdmin&...` | Admin / help page (AdminPage tab, JSP-rendered) | TeamCity admin |
| `POST` | `/admin/bridge/saveSecret.html` | Set or clear the webhook HMAC secret (form on the admin page) | TC `CHANGE_SERVER_SETTINGS` + CSRF token |
| `POST` | `/admin/bridge/runTests.html` | Run the self-test battery (button on the admin page) | TC `CHANGE_SERVER_SETTINGS` + CSRF token |

```mermaid
flowchart LR
    classDef pub fill:#e8f5e9,stroke:#2e7d32
    classDef sec fill:#fff3e0,stroke:#f57c00

    A[GitHub App] -->|"POST /webhook<br/>X-Hub-Signature-256"| B[PluginWebhookController]:::sec
    C[Operator / curl] -->|"GET /info"| D[WebhookInfoController]:::pub
    E[Wiki / runbook] -->|"GET /info.md"| D
```

## POST /webhook

Receives GitHub webhook deliveries. Always verifies the signature
before processing.

### Request

| Header | Required | Meaning |
|---|---|---|
| `X-GitHub-Event` | yes | The event type (`ping`, `pull_request`, etc.) |
| `X-Hub-Signature-256` | yes | `sha256=<hex>` HMAC over the raw body |
| `Content-Type` | yes | Must be `application/json` |
| `X-GitHub-Delivery` | optional | Unique delivery ID (logged; will be used for replay protection in a future version) |

Body: a JSON payload as documented in
[GitHub's webhook events reference](https://docs.github.com/en/webhooks/webhook-events-and-payloads).

### Responses

| Status | Meaning |
|---|---|
| `200 OK` | Event handled. Body: `pong` for `ping`, empty otherwise. |
| `204 No Content` | Event recognised but not acted on (e.g. `push` in current version). |
| `400 Bad Request` | `X-GitHub-Event` header missing. |
| `401 Unauthorized` | Signature missing, malformed, or invalid; or `teamcity.github.bridge.webhook.secret` not configured. Body: `Invalid signature`. |

### Handled actions

For `pull_request`, the plugin acts only on `action=ready_for_review`.
Other actions return `200 OK` without enqueuing anything.

### Example: a ping delivery

```http
POST /app/teamcity-github-bridge/webhook HTTP/1.1
Host: teamcity.example.com
X-GitHub-Event: ping
X-Hub-Signature-256: sha256=4bf6...
Content-Type: application/json

{"zen": "Non-blocking is better than blocking."}
```

Response:

```http
HTTP/1.1 200 OK
Content-Type: text/plain; charset=UTF-8

pong
```

### Example: a ready_for_review delivery

```http
POST /app/teamcity-github-bridge/webhook HTTP/1.1
Host: teamcity.example.com
X-GitHub-Event: pull_request
X-Hub-Signature-256: sha256=8e2f...
Content-Type: application/json

{
  "action": "ready_for_review",
  "number": 189,
  "pull_request": {
    "number": 189,
    "head": {"ref": "Feature/raycast", "sha": "deadbeef1234..."},
    "base": {"ref": "main"}
  },
  "repository": {"full_name": "acme/widget"}
}
```

Response:

```http
HTTP/1.1 200 OK
```

### Manual test

```bash
secret='your-secret'
body='{"zen": "test"}'
sig=$(printf '%s' "$body" | openssl dgst -sha256 -hmac "$secret" | awk '{print $2}')

curl -i \
    -H "X-GitHub-Event: ping" \
    -H "X-Hub-Signature-256: sha256=$sig" \
    -H "Content-Type: application/json" \
    --data "$body" \
    https://<TC_HOST>/app/teamcity-github-bridge/webhook
```

You should get `200 pong`.

## GET /info

Returns the live webhook configuration as JSON, computed from the
current request (host, scheme) plus the plugin's known config.

### Request

No body, no auth.

```bash
curl https://<TC_HOST>/app/teamcity-github-bridge/info
```

### Response

```json
{
  "payloadUrl": "https://teamcity.example.com/app/teamcity-github-bridge/webhook",
  "contentType": "application/json",
  "sslVerification": true,
  "recommendedEvents": [
    "pull_request",
    "pull_request_review",
    "push",
    "check_suite",
    "ping"
  ],
  "secretConfigured": true,
  "logFile": "/data/teamcity_server/datadir/logs/teamcity-github-bridge.log",
  "logConfigured": true,
  "pluginVersion": "TeamCity 2026.1 (build 222521)"
}
```

| Field | Type | Meaning |
|---|---|---|
| `payloadUrl` | string | The absolute URL GitHub should POST events to. Computed from the request's host, scheme, port, and context path (with `X-Forwarded-Proto` and `X-Forwarded-Host` respected when present). |
| `contentType` | string | Always `application/json`. The plugin does not parse `application/x-www-form-urlencoded`. |
| `sslVerification` | boolean | `true` when the request reached the plugin over HTTPS (after honouring `X-Forwarded-Proto`). Reflected so the operator copies the right value into GitHub. |
| `recommendedEvents` | array of string | The events the operator should subscribe to in the App. Currently acted on: `pull_request` (action `ready_for_review`), `ping`. Forward-listed: `pull_request_review`, `push`, `check_suite`. |
| `secretConfigured` | boolean | `true` when `teamcity.github.bridge.webhook.secret` is set to a non-blank value. **Never echoes the secret itself.** |
| `logFile` | string | Absolute path where the plugin's dedicated log file lives (or *would* live) - always `<TC_DATA_DIR>/logs/teamcity-github-bridge.log`. |
| `logConfigured` | boolean | `true` when the dedicated log file currently exists, i.e. an operator has merged the log4j snippet (`teamcity-github-bridge-log4j-snippet.xml`) into `teamcity-server-log4j.xml`. |
| `pluginVersion` | string | The TeamCity server version string. Used as a sanity check (the plugin is alive). |

### Response codes

| Status | Meaning |
|---|---|
| `200 OK` | Always, when the plugin is loaded. |
| `404 Not Found` | The plugin is not loaded or the controller did not register. |

## GET /info.md

Same content as `/info` but rendered as Markdown for copy-paste
into a runbook or wiki.

### Example

```bash
curl https://<TC_HOST>/app/teamcity-github-bridge/info.md
```

Output:

```markdown
# GitHub App Webhook Configuration

Configure these values on your GitHub App webhook page
(`https://github.com/settings/apps/<your-app>` -> Webhook).

| Field | Value |
|-------|-------|
| Payload URL | `https://teamcity.example.com/app/teamcity-github-bridge/webhook` |
| Content type | `application/json` |
| SSL verification | Enable |
| Secret | configured server-side - reuse the same value |

## Subscribe to events

- `pull_request`
- `pull_request_review`
- `push`
- `check_suite`
- `ping`

Plugin version: TeamCity 2026.1 (build 222521)
```

## Admin page

The plugin registers an `AdminPage` tab visible at:

```
<TC_URL>/admin/admin.html?item=<...>&tab=bridgeAdmin
```

Navigate to it via `Administration -> Server Administration ->
GitHub Bridge` in the sidebar (the page is grouped under
`SERVER_RELATED_GROUP`).

The page shows:

- Plugin and TC versions.
- Webhook URL, with the secret and dedicated-log configuration
  status (red / yellow / green chips).
- Last 100 webhook deliveries in memory (event, action, repository,
  HTTP status, outcome, detail). Cleared on server restart; the
  dedicated log is the long-term record.
- A copy-paste card with the GitHub App webhook quick-config.
- A Help section linking to every page under `doc/` on GitHub plus
  a "Common 401 / 404 troubleshooting" fold.

Access is gated by TeamCity's standard admin auth - the JSP is
served from inside `AdminPage`, which inherits TC's admin
authorization filter.

## POST /admin/bridge/saveSecret.html

Mutates the plugin-owned settings file
`<TC_DATA_DIR>/config/teamcity-github-bridge.properties`. Backs the
"HMAC secret" form on the admin page.

### Request

POST, `Content-Type: application/x-www-form-urlencoded`, form fields:

| Field | Required | Values | Meaning |
|---|---|---|---|
| `action` | yes | `set` or `clear` | What to do. |
| `secret` | yes when `action=set` | non-blank string | New secret value. |
| `tc-csrf-token` | yes | TC's per-session CSRF token | Without it TC's `CSRFFilter` rejects the request with 403. |

### Responses

| Status | Meaning |
|---|---|
| `302` (redirect to the admin page) | Operation accepted; the redirected admin page shows a banner indicating result. |
| `401 Unauthorized` | No user session. |
| `403 Forbidden` | The user does not have `CHANGE_SERVER_SETTINGS`, **or** CSRF token missing/invalid. |

The endpoint never echoes the new secret back. Server log records
`Webhook secret updated by <username>` at INFO level.

## POST /admin/bridge/runTests.html

Runs the full self-test battery against the live plugin instance.
Backs the "Run self-tests" button on the admin page.

### Request

POST, no body fields except CSRF.

| Field | Required | Meaning |
|---|---|---|
| `tc-csrf-token` | yes | Per-session CSRF token. |

### Responses

| Status | Meaning |
|---|---|
| `302` (redirect with `?bridgeResult=tested`) | Tests ran; results stashed in the user session and rendered on the next admin-page GET. |
| `401 Unauthorized` | No user session. |
| `403 Forbidden` | Missing `CHANGE_SERVER_SETTINGS` or invalid CSRF token. |

The tests run synchronously (target: under 5 seconds) and the
result list is one-shot: it is consumed on the next admin-page
render then cleared from the session.

### Tests run (as of v0.9.3)

1. **Webhook secret configured** - is `teamcity.github.bridge.webhook.secret` set anywhere.
2. **Dedicated log file** - has `PluginLogConfigurator` attached the appender, or has an operator wired one manually.
3. **GitHub API reachable** - `GET https://api.github.com/zen` without auth.
4. **HMAC roundtrip** - sign a known payload with the configured secret, verify with `SignatureVerifier`.
5. **Webhook self-delivery** - HMAC-signed POST to our own webhook URL; expects `200 pong`.
6. **Token resolution / `<project>` / `<repo>`** - one row per opted-in buildType project; uses `TokenResolver.resolveAccessToken`.
7. **GitHub API auth / `<project>` / `<repo>`** - one row per project that produced a token; `GET /rate_limit` with the token.

Each test reports `PASS` / `WARN` / `FAIL` / `SKIP` with a free-form
detail string.

## Versioning

The endpoint paths are stable and considered API for this plugin.
Field names in the `/info` JSON response are additive: future
versions may add fields but will not rename or remove existing
ones. Operators may parse the JSON safely; the contract is to
ignore unknown fields.

If a breaking change is ever needed, a new path like
`/app/teamcity-github-bridge/v2/info` will be introduced alongside
the old one.

## Discoverability

The endpoints have no UI yet; you discover them via this document.
Future versions may surface them in the TeamCity admin UI (under a
"GitHub Bridge" tab).

## Related pages

- [webhook-setup.md](webhook-setup.md) - how to use `/info` when
  configuring the App.
- [security.md](security.md) - signature verification details.
- [troubleshooting.md](troubleshooting.md) - what to do on 401 or
  404.
