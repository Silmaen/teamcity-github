---
name: Bug report
about: Something the plugin does, or fails to do, on a real server
labels: bug
---

## What happened

<!-- What you expected on the pull request or in TeamCity, and what you got. A
screenshot of the Checks panel or of the build queue usually says it fastest. -->

## Versions

|                          |                                                  |
|--------------------------|--------------------------------------------------|
| Plugin version           | <!-- admin page, or the plugin list -->          |
| TeamCity version + build | <!-- e.g. 2026.1 (222521) -->                    |
| GitHub                   | <!-- github.com, or GitHub Enterprise <host> --> |

## Configuration

- `prBuildRef`: <!-- pull (default) or branch -->
- Does the build configuration carry the **GitHub Bridge integration** feature
  directly, or inherit it from a template?
- Relevant flags you changed from the defaults (admin page and build feature):

## Self-tests

<!-- Admin page -> Run self-tests. Paste the failing rows, or say "all pass". -->

## Logs

<!-- From <TC_DATA_DIR>/logs/teamcity-github-bridge.log, around the event.
Set the plugin's categories to DEBUG if the INFO lines say nothing:
io.github.dlachouette.teamcity.github.*

REDACT anything secret: no App private key, no webhook secret, no ghs_* token,
no API token. -->

```
```

## Anything else

<!-- Whether it is reproducible, whether it started with an upgrade, whether a
personal build or a build chain is involved. -->
