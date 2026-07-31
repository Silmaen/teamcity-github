# Security policy

## Reporting a vulnerability

**Do not open a public issue.** Use GitHub's private vulnerability reporting on
this repository — *Security* → *Report a vulnerability* — which opens a draft
advisory only the maintainers can see.

Please include the TeamCity version, the plugin version (it is on the admin page
and in the plugin list), whether the server talks to github.com or a GitHub
Enterprise host, and the smallest sequence of steps that shows the problem.

**Never paste a secret into a report**: the GitHub App private key, the webhook
HMAC secret, an installation token (`ghs_*`) or the plugin's API token. If a
log excerpt is useful, redact them — the plugin never logs a key body or a
token, so an excerpt that contains one is itself worth reporting.

## Supported versions

The latest released `1.x` is the only supported version. Fixes go into a new
release rather than into patches of older ones; the plugin is a single zip and
upgrading is a drop-in replacement.

| Version | Supported |
|---|---|
| latest `1.x` | yes |
| anything older | no — upgrade first |

## What the plugin defends, and how

The full model — trust boundaries, what the webhook verification does and
deliberately does not do, token handling, the anonymous endpoints and why they
are anonymous — is in **[doc/security.md](doc/security.md)**. In short:

- **Inbound webhooks are fail-closed.** HMAC-SHA256 over the raw body is
  mandatory: a missing, malformed or wrong signature is a 401 *before* the
  payload is parsed. Comparison is constant-time.
- **Bodies are bounded** (25 MB → 413) before verification, so an oversized
  payload cannot be used to exhaust the server.
- **Deliveries cannot be replayed** while replay protection is on (default): a
  repeated `X-GitHub-Delivery` is acknowledged and dropped.
- **Secrets are never echoed.** The admin page reports a secret's *presence*, the
  logs identify a bad key by its `BEGIN` line and category, never by content, and
  tokens are treated as opaque strings end-to-end.
- **The external API is off until you give it a token**, and the token is
  compared in constant time.
- **Commands from GitHub are author-gated**: a comment or review that starts a
  build must come from an author whose `author_association` is on the allowlist
  (`OWNER,MEMBER,COLLABORATOR` by default).

## Scope

In scope: the webhook endpoint, signature verification and replay protection,
token minting and storage, the `/api/` endpoints and their authentication, the
admin pages (CSRF, what they display), and anything that could make the plugin
run a build or publish a status on behalf of someone who should not be able to.

Out of scope: vulnerabilities in TeamCity itself or in GitHub, and configuration
choices the plugin warns about (running with no webhook secret, for instance, is
refused rather than allowed — but disabling replay protection is your call).
