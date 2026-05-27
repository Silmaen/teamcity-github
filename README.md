<p align="center">
  <img src="doc/assets/logo-wordmark.svg" alt="teamcity-github-bridge" width="540"/>
</p>

# teamcity-github-bridge

> A TeamCity 2026.1+ server-side plugin that closes the gap between
> TeamCity and GitHub: draft PR awareness, automatic retrigger on
> ready-for-review, App-level webhooks with HMAC verification, rich
> GitHub Check Runs that carry the build's actual status text, and a
> native admin page in TeamCity's UI.

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![TeamCity](https://img.shields.io/badge/TeamCity-2026.1%2B-success.svg)](https://www.jetbrains.com/teamcity/)
[![Build](https://img.shields.io/badge/build-Docker--only-blue.svg)](doc/development.md)
[![Version](https://img.shields.io/badge/version-1.3.0-blue.svg)](#status)
[![Status](https://img.shields.io/badge/status-stable-success.svg)](#status)

---

## The problem

TeamCity 2026.1's bundled GitHub integration leaves a few sharp edges
that bite real pipelines:

| Pain point | Effect on day-to-day work |
|---|---|
| `teamcity.pullRequest.isDraft` is not exposed | DSL has no clean way to skip a build for draft PRs. |
| `pullRequests { ignoreDrafts = true }` is silently ignored with GitHub App auth | Drafts get built anyway, burning CI minutes. |
| No retrigger when a PR transitions from draft to ready | Builds that were "skipped via service message" stay green - the PR can be merged with un-validated changes. **This is the safety bug that motivated the plugin.** |
| No App-level webhook endpoint, only per-repo webhooks via `teamcity-commit-hooks` | One webhook per repo to maintain by hand. |
| Hardcoded "TeamCity build finished" message on commit statuses | The build status text never reaches GitHub. |

This plugin is the place to fix these things outside of the JetBrains
release cycle.

## What you get

```mermaid
flowchart LR
    classDef solved fill:#e8f5e9,stroke:#43a047,color:#1b5e20
    classDef plugin fill:#e3f2fd,stroke:#1976d2,color:#0d47a1

    subgraph TeamCity["TeamCity 2026.1+"]
        SDK[Bundled GitHub SDK]
        BRIDGE[teamcity-github-bridge]:::plugin
    end

    DRAFT["Draft PR check<br/>via GitHub REST"]:::solved
    RETRIGGER["Auto retrigger on<br/>ready_for_review"]:::solved
    WEBHOOK["App-level webhook<br/>+ HMAC verify"]:::solved
    INFO["/info endpoint<br/>(config snapshot)"]:::solved

    SDK -. unchanged .- BRIDGE
    BRIDGE --> DRAFT
    BRIDGE --> RETRIGGER
    BRIDGE --> WEBHOOK
    BRIDGE --> INFO
```

Concretely:

- **Suppresses builds for draft PRs** via a `StartBuildPrecondition`
  (per-buildType opt-in - paused configs are untouched). Manual
  user triggers ("Run" from the TC UI) bypass the gate so an
  operator can always force a build to run on a draft PR.
- **Tags every opted-in PR build with `draft` / `ready`** the moment
  it hits the queue (`PrPromotionTagger`) so the queue UI shows at a
  glance which builds are deliberately held versus agent-starved.
- **Publishes a GitHub Check Run at every lifecycle transition** —
  `queued` when the build enters the TC queue, `in_progress` on
  start, `cancelled` on `buildInterrupted` /
  `buildRemovedFromQueue`, `success`/`failure`/`cancelled` on
  `buildFinished`, and `skipped` for draft-held builds. Each Check
  Run carries a `details_url` that jumps directly to the build page
  in TC. Propagates the build's `statusDescriptor.text` into
  GitHub's PR UI instead of the hard-coded
  `"TeamCity build finished"` from the bundled publisher.
- **Listens for `pull_request.ready_for_review`** and enqueues every
  matching build configuration. No more "merged with stale green
  checks".
- **One webhook URL for the whole GitHub App** instead of one per
  repository. HMAC-SHA256 verification is mandatory and fail-closed.
- **`/info` endpoint** that returns the live webhook configuration
  (URL, recommended events, secret status, log path) as JSON or
  Markdown. Paste-ready into GitHub's App settings.
- **Native admin page** at `Administration -> Server Administration
  -> GitHub Bridge` showing plugin status, recent events, and help
  links.
- **Dedicated log file** at `<TC_DATA_DIR>/logs/teamcity-github-bridge.log`
  via a shipped log4j snippet.
- **Visual pill rendering** of the `draft` / `ready` tags in TC
  build lists (client-side CSS via a `SimplePageExtension`).
- **Forward-compatible with the new stateless `ghs_*` token format**
  - tokens are treated as opaque end-to-end.
- **Self-mints its own installation tokens** from the App's private
  key (signed JWT + `POST /app/installations/{id}/access_tokens`),
  so the plugin works on a vanilla TeamCity 2026.1 sandbox without
  any prior interaction with TC's connection cache.

## Quick start

Everything runs in Docker - nothing is installed on the host.

```bash
# Build the plugin archive
./dev package
# -> target/teamcity-github-bridge-1.3.0.zip

# Drop it into your TeamCity Data Dir and restart
cp target/teamcity-github-bridge-*.zip <TC_DATA_DIR>/plugins/
```

Then follow the three setup pages:

1. [Set up the GitHub App](doc/github-app-setup.md) (~5 min)
2. [Configure the App-level webhook](doc/webhook-setup.md) (~3 min)
3. [Enable the bridge on a build configuration](doc/configuration.md#enable-on-a-build-configuration) (~1 min per build type)

## Architecture at a glance

```
 GitHub                                  TeamCity
+----------+                          +-----------------------------+
|          |  pull_request webhook    |  /webhook  (HMAC verified)  |
|  Repo  ============================>|  PluginWebhookController    |
|          |                          |              |              |
|          |                          |              v              |
|          |                          |  WebhookPayloadParser       |
|          |                          |              |              |
|          |                          |   opened/synchronize/       |
|          |                          |   ready_for_review?         |
|          |                          |              |              |
|          |                          |              v              |
|          |                          |  PullRequestEventListener   |
|          |                          |    scan ProjectManager      |
|          |                          |    -> enqueue matching      |
|          |                          |       BuildTypes            |
|          |                          |                             |
|          |                          |  +-----------------------+  |
|          |                          |  | DraftAwareBuildFilter |  |
|          |                          |  | (per pre-start hook)  |  |
|          |                          |  +-----------+-----------+  |
|          |                          |              |              |
|          |  GET /repos/.../pulls/N  |              v              |
|          |<==============================  GitHubClient           |
|          |  Bearer ghs_xxxx...      |              ^              |
|          |  X-GitHub-Api-Version    |              |              |
|          |                          |        TokenResolver        |
|          |                          |              |              |
|          |                          |              v              |
|          |  POST /app/installations |        AppTokenMinter       |
|          |  /{id}/access_tokens     |        (signs RS256 JWT     |
|          |<==============================   with the App's key   |
|          |  Bearer <JWT>            |         + caches ghs_*)     |
+----------+                          +-----------------------------+
```

See [doc/architecture.md](doc/architecture.md) for the full picture
(component diagram, sequence diagrams, threading model).

## Documentation map

> **Tip for AI readers**: each page below is self-contained. Start
> with the linked page closest to the question you are answering;
> they cross-link rather than nest.

### Get it running

- [Installation](doc/installation.md) - build the zip, drop it in
  the data dir, verify the load.
- [GitHub App setup](doc/github-app-setup.md) - create the App,
  grant the right permissions, install on repos, wire up the
  TeamCity connection.
- [Webhook setup](doc/webhook-setup.md) - configure the App-level
  webhook using the live `/info` endpoint.
- [Configuration reference](doc/configuration.md) - every parameter
  the plugin understands.

### Operate it

- [Usage scenarios](doc/usage-scenarios.md) - what happens for each
  PR lifecycle event (open, draft, ready, merge, force-push, etc.),
  with sequence diagrams.
- [HTTP API reference](doc/api-reference.md) - `/webhook`, `/info`,
  `/info.md` with curl examples.
- [Troubleshooting](doc/troubleshooting.md) - common failure modes
  and how to read the logs.

### Understand it

- [Architecture](doc/architecture.md) - components, data flow,
  threading, extension points.
- [Security model](doc/security.md) - trust boundaries, signature
  verification, fail-closed defaults, token opacity.

### Contribute

- [Developer guide](doc/development.md) - building with Docker,
  running tests, layout, conventions, how to add a feature.

### Project meta

- [Changelog](CHANGELOG.md) - per-version change log.
- [Contributing](CONTRIBUTING.md) - build, test, coding conventions, how to release.
- [Roadmap](doc/roadmap.md) - forward-looking work items.
- [Historical](doc/historical/) - early design transfer documents kept for context, not maintained.

## Status

**Stable**. Current version is **1.3.0**. 110 unit tests pass.
The plugin has been installed end-to-end against both vanilla
github.com and a live GitHub Enterprise (`github.example.com`)
TeamCity 2026.1 server. The in-product self-test battery
validates webhook delivery, HMAC verification, token issuance
(via the plugin's own self-mint path) and the GitHub REST
round-trip - 35/35 PASS on a correctly-configured installation.

The public API surface (the `teamcity.github.bridge.*` namespace,
the `/app/teamcity-github-bridge/*` endpoints, the
`/admin/bridge/*` form actions) is stable. Future minor releases
may add fields and endpoints; they will not rename or remove what
already exists.

See [CHANGELOG.md](CHANGELOG.md) for the per-version change log.
See [doc/roadmap.md](doc/roadmap.md) for what comes after 1.3.

## License

Apache License 2.0 - see [LICENSE](LICENSE).
