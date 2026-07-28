# Documentation

Start here, then jump to the page for your task.

## New here?

➡️ **[Quickstart (5 minutes)](quickstart.md)** — install → create the
GitHub App → configure a project → see a Check Run.

## By task

| I want to… | Page |
|---|---|
| Install or upgrade the plugin | [installation.md](installation.md) |
| Get the GitHub App set up (auto or manual) | [github-app-setup.md](github-app-setup.md) |
| Configure the webhook (manual path only) | [webhook-setup.md](webhook-setup.md) |
| Know every setting and where it lives | [configuration.md](configuration.md) |
| See concrete end-to-end walkthroughs | [usage-scenarios.md](usage-scenarios.md) |
| Map our branch model (default / `Release/*` / cascade / QA) onto pipelines | [branching-workflows.md](branching-workflows.md) |
| Follow the decisions, the gap backlog and what is actually implemented | [tasks/branching-worklog.md](tasks/branching-worklog.md) |
| Call the plugin's HTTP API from another app | [api-reference.md](api-reference.md) |
| Understand the security/trust model | [security.md](security.md) |
| Diagnose a problem | [troubleshooting.md](troubleshooting.md) |
| Understand the internals | [architecture.md](architecture.md) |
| Build or contribute to the plugin | [development.md](development.md) |
| See what's shipped and planned | [roadmap.md](roadmap.md) |

## The 30-second model

- You opt a **build configuration** in by adding the **GitHub Bridge
  integration** build feature to it.
- The **repository** and **connection** are set once per project on the
  project's **GitHub Bridge** tab (`connectionId=managed` uses the
  server-managed App; or point it at a TeamCity connection).
- The plugin then posts a GitHub **Check Run** through every build
  lifecycle event, holds/skips draft PRs, retriggers on ready-for-review,
  and (optionally) reacts to PR comments, approvals and the re-run button.
