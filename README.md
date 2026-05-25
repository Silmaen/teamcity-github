# teamcity-github-bridge

> A TeamCity 2026.1+ server-side plugin that closes the gap between
> TeamCity and GitHub: draft PR awareness, automatic retrigger on
> ready-for-review, App-level webhooks with HMAC verification, rich
> GitHub Check Runs that carry the build's actual status text, and a
> native admin page in TeamCity's UI.

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![TeamCity](https://img.shields.io/badge/TeamCity-2026.1%2B-success.svg)](https://www.jetbrains.com/teamcity/)
[![Build](https://img.shields.io/badge/build-Docker--only-blue.svg)](doc/development.md)
[![Version](https://img.shields.io/badge/version-0.8.0-blue.svg)](#status)
[![Status](https://img.shields.io/badge/status-beta-yellow.svg)](#status)

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
  (per-buildType opt-in - paused configs are untouched).
- **Tags held PRs with `draft` / `ready`** the moment they hit the
  queue (`PrPromotionTagger`) so the queue UI shows at a glance
  which builds are deliberately held versus agent-starved.
- **Publishes a GitHub Check Run** at every lifecycle event:
  `skipped` for held drafts (`DraftCheckRunReporter`), `in_progress`
  on start and `success`/`failure`/`cancelled` on finish
  (`BuildStatusCheckRunPublisher`) - propagating the build's
  `statusDescriptor.text` into GitHub's PR UI instead of the
  hard-coded `"TeamCity build finished"` from the bundled publisher.
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

## Quick start

Everything runs in Docker - nothing is installed on the host.

```bash
# Build the plugin archive
./dev package
# -> target/teamcity-github-bridge-0.5.0.zip

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
|          |                          |   ready_for_review?         |
|          |                          |              |              |
|          |                          |              v              |
|          |                          |  ReadyForReviewListener     |
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
|          |                          |        (OAuthTokensStorage) |
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

### Historical context

- [TC 2026.1 knowledge base](doc/teamcity-plugin-knowledge-base.md)
  (French) - the transfer document that motivated this plugin. Why
  TeamCity behaves the way it does, what we tried first, what
  trapdoors we hit.

## Status

Beta. Current version is **0.8.0**. 81 unit tests pass. The plugin
has been installed end-to-end on a real TeamCity 2026.1 server
(`builder.argawaen.net`) and a signed GitHub App webhook delivery
has been validated through to `200 pong`. Iterations 1 through 4 of
the roadmap have shipped:

- v0.2.0: queue tag for draft/ready (`PrPromotionTagger`),
  dedicated log file.
- v0.3.0: admin page in TC's UI + in-memory recent events log.
- v0.4.0: rich GitHub Check Runs that carry the build's actual
  status text.
- v0.5.0: pill rendering of draft/ready tags via a client-side
  Page Extension. **Gap 2 spike** (server-side branch column
  override) concluded - the SDK does not expose the needed
  extension point in 2026.1; the cosmetic mitigation ships, the
  full server-side fix is parked until JetBrains lands the SPI.
- v0.6.0: self-managed dedicated log file (no log4j snippet to
  merge) + webhook secret editable directly from the admin page
  (no more shell access to `internal.properties` required).
- v0.7.0: `BuildStatusCheckRunPublisher` covers main + opt-out PR
  builds (Gap A4 in the roadmap). Consumers can now retire the
  bundled `commitStatusPublisher` on every opted-in buildType
  without losing GitHub PR coverage.
- v0.7.1: `TokenResolver` accepts both `CID_<hash>` and
  `PROJECT_EXT_<N>` for `teamcity.github.bridge.connectionId`; warns are
  rate-limited to one per minute per (project, id) pair.
- v0.8.0: new build parameter `teamcity.github.bridge.isdraft`
  (`true`/`false`) exposed to every opted-in build - closes the
  long-standing knowledge-base gap about `teamcity.pullRequest.isDraft`
  not being published by TC.

Open items are tracked in
[doc/roadmap.md](doc/roadmap.md#sequencing).

See the [milestone roadmap](doc/development.md#roadmap) for what's
planned, and [doc/roadmap.md](doc/roadmap.md) for the deep-dive on the
three highest-priority gaps (held-draft tagging, enriched status
publisher, branch display).

## License

Apache License 2.0 - see [LICENSE](LICENSE).
