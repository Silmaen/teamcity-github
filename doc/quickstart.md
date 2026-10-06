# Quickstart (5 minutes)

The fastest path from a fresh install to a green Check Run on a pull
request. It uses the **managed GitHub App** flow, so you never copy a
private key or hand-configure a webhook.

> Prefer to wire an existing GitHub App by hand? Skip to
> [github-app-setup.md → Option B](github-app-setup.md). Everything here
> has a manual equivalent.

---

## 1. Install the plugin

Upload `teamcity-github-bridge-<version>.zip` in
**Administration → Plugins → Upload plugin zip**, then restart the
TeamCity server. See [installation.md](installation.md) for details.

After restart you should see **Administration → GitHub Bridge** in the
left menu.

## 2. Create & install the GitHub App

1. Open **Administration → GitHub Bridge**, tab **GitHub App**.
2. Optionally type your GitHub organisation
   (leave blank for a personal App), then click **Create GitHub App**.
3. GitHub shows a confirmation screen pre-filled with the right webhook
   URL, permissions and events. Click **Create**.
4. You land back on the admin page: the App's credentials **and webhook
   secret** are now stored automatically.
5. Click **Install / manage installations** (same tab) and
   install the App on the repositories you want TeamCity to report on.

That's the entire GitHub side — no `.pem`, no manual webhook.

## 3. Point a project at the App

1. Open **Administration → \<your project\> → GitHub Bridge**.
2. On the **Repository** tab, set:
   - **GitHub repository**: `owner/name` (e.g. `acme/widgets`)
   - **GitHub App connection ID**: `managed`
3. Leave the **Triggers** tab at its defaults and **Save**.

## 4. Opt a build configuration in

In the build configuration you want to report to GitHub:

1. **Build Features → Add build feature → GitHub Bridge integration**.
2. **Remove (or disable) the bundled `commitStatusPublisher` build feature
   on the same build configuration**, if it has one.
3. Save. (The defaults run on branches and ready PRs; tick **Run on PR
   (draft)** in the **Triggers** section to build drafts too.)

> ⚠️ **Step 2 is not optional in practice.** The bridge and TeamCity's
> bundled *Commit status publisher* both report to GitHub, so leaving both
> enabled produces **two competing rows per build** — a rich Check Run from
> the bridge and a generic `"TeamCity build finished"` Commit Status from
> TeamCity. The bridge **never disables the bundled feature for you** (it
> only warns): choosing what reports to GitHub is your configuration
> decision, not the plugin's. See
> [configuration.md](configuration.md#check-run-publisher-coexistence-with-the-bundled-commitstatuspublisher).

## 5. Verify

- On the admin page, tab **GitHub App**, click **Verify App configuration** —
  it should report *configuration OK*.
- Tab **Overview**, click **Run self-tests** — all checks should pass (a
  `WARN` on a configuration check names what to look at).
- Open (or reopen) a pull request on the repo. Within a few seconds a
  **`TeamCity / <build configuration>`** Check Run appears on the PR and
  transitions queued → in progress → success/failure.

Done. 🎉

---

## Where to go next

| You want to… | Read |
|---|---|
| Understand every setting | [configuration.md](configuration.md) |
| Trigger builds from PR comments, run-on-approval, path filters | [configuration.md](configuration.md) · [usage-scenarios.md](usage-scenarios.md) |
| Drive the plugin from another app (HTTP API) | [api-reference.md](api-reference.md) |
| Diagnose a problem | [troubleshooting.md](troubleshooting.md) |
| Review the security model | [security.md](security.md) |

If something went wrong, the admin page's **Activity** tab and the dedicated
log are the first places to look.
