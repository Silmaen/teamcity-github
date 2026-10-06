# Upgrading

The plugin is a single zip and an upgrade is a drop-in replacement — see
[installation.md](installation.md#step-2-deploy) for the mechanics. This page
covers the part the mechanics do not: what changes **for the operator** on the
GitHub side and in what the reviewers see, release by release, newest first.

Only the latest `1.x` is supported ([SECURITY.md](../SECURITY.md#supported-versions)),
so upgrade straight to the newest release and read every section between your
version and it.

## The short version, every time

1. Take a copy of `<TC_DATA_DIR>/config/teamcity-github-bridge.properties`.
   It is the only setting state the plugin owns, and it is the only thing a
   rollback needs. (The plugin also keeps a cache of the Check Runs it left
   open, under `<TC_DATA_DIR>/system/pluginData/teamcity-github-bridge/`;
   losing it only loses the repair described below.)
2. Drop the new zip in, restart (or hot-upload).
3. On `Administration -> Server Administration -> GitHub Bridge`: check the
   version, run **Verify App configuration**, then **Run self-tests**.
4. Open one pull request build and confirm its Check Run still looks right.

Settings are read by key, so a key an older or newer version does not know is
**ignored, not rejected** — the file survives an upgrade and a rollback
unchanged.

## To the next release (unreleased)

One default changes; nothing else needs touching.

### Rows left open are concluded on their own

The bridge now remembers each Check Run it left `queued` or `in_progress`, in
`<TC_DATA_DIR>/system/pluginData/teamcity-github-bridge/open-check-runs.tsv`,
and a few minutes after startup — then every ten minutes — posts the conclusion
TeamCity now knows for any row whose own event was missed (a server restart
mid-build, a failed post). A row opened before this release is not in the file,
so a row stuck from before the upgrade still needs one manual re-run.

### Opt-in: assign a new pull request to its author

The project setting **Assign to the author**
(`teamcity.github.bridge.autoAssignAuthor`) is off by default. Turning it on
needs the App's **Issues: write**: accept the permission on the App's
installation page first, or each assignment logs a `403`.

### Superseded builds are skipped, not cancelled

A build the bridge stops because a newer commit was pushed now concludes
`skipped` (*"Superseded by <short sha>"*) instead of `cancelled`. Nothing to
change: the required checks of the pull request are on its new head.

### Draft pull requests are opt-in

`triggerOnPrDraft` now defaults to **off**. A feature saved from the UI already
stores its value and keeps behaving as before; one that never stored it —
typically a Kotlin DSL configuration — stops running on drafts and posts
*"Skipped: draft PR"* instead. Where drafts should still build, set
`param("triggerOnPrDraft", "true")`. The self-test **Draft setting along
composite chains** lists the composites that would build a draft-skipping
dependency anyway.

### A required check can keep its name

The feature's new **Check name** (`checkName`) fixes a configuration's Check Run
name, so moving it in the project tree no longer renames the check a branch
protection rule requires. Setting it on an existing check **is** a rename, with
the same consequence as `checkName.stripPrefix` below: update the rule in the
same change. The self-test **Unique check names** warns when two configurations
post the same name to one repository.

### A self-test reads branch protection

**Required checks / `<repo>`** compares the checks your branches require with
the names the bridge posts, and warns about a required name nothing posts. It
reads rulesets with the App's existing permissions. To cover classic branch
protection too, grant the App **administration: read** (repository
permission); without it the row says classic protection was not read. Nothing
else uses that permission, and nothing is ever written.

## To 1.10.0

Nothing breaks on upgrade, and no setting needs to change for the plugin to keep
behaving as it did. Four things are worth ten minutes.

### 1. Revoke the App's `pull_requests: write` — it is no longer needed

The sticky PR summary comment is gone (see the
[CHANGELOG](../CHANGELOG.md#1100---2026-08-01) for why), and it was the only
thing that ever wrote to a pull request. The plugin's only write is now the Check
Run lifecycle, which is the **Checks** permission.

- **New installations** get this for free: the managed-App manifest asks for
  `pull_requests: read`.
- **Existing installations** keep the write permission GitHub already granted
  them. Nothing misbehaves if you leave it — but it is a permission taken for no
  reason.

To drop it: GitHub App settings → *Permissions & events* → **Pull requests** →
`Read-only` → save, then accept the permission change on the installation (GitHub
asks for that separately, per installation).

> **Then click "Clear cached tokens" on the admin page.** An installation token
> carries the permissions it had **when it was minted**, and the plugin caches it
> for 50 minutes. Without clearing, the plugin keeps using a token minted under
> the old scopes for up to half an hour — which matters more in the other
> direction, when you *grant* a permission and every call keeps failing with
> `403` for a permission the App visibly has. The button is new in 1.10.0, next
> to *Verify App configuration*.

### 2. Remove `prComment.enabled` from the settings file

The key is gone from the code, so a leftover line is inert rather than harmful —
but it now reads like a setting that exists. Delete it.

If anything in your workflow depended on that comment, its replacement is the
Checks panel: the same per-configuration rows, the same status, and the same
artifact download links (`checkRun.artifactLinks`, on by default).

### 3. Three defaults change what reviewers see — no action, but do not be surprised

All three are on by default and can be switched off on the admin page.

| What changed | What a reviewer now sees | If you would rather not |
|---|---|---|
| **Test outcome in the title** (`checkRun.testStats`) | The merge box says `Build failed — 3 of 1046 tests failed (2 new)`, or `Build passed — 31 tests passed, 12 ignored`, instead of just `Build failed`. The body lists the failing tests, new failures first. | Untick *Report the test outcome*. |
| **Timings in the summary** (`checkRun.timings`) | Total / working time / wait split under the title, and GitHub renders the duration itself (the plugin now sends `started_at` and `completed_at`). | Untick *Report the build's timings*. |
| **An infrastructure failure is named** | The title reads `Infrastructure failure: Unable to collect changes` rather than looking like a failing test. The conclusion is **still `failure`** and the merge stays blocked. | Nothing to turn off — naming the cause is always on. The *unblocking* half is opt-in and **off**: `checkRun.infraNeutral`. |

**If anything of yours parses Check Run titles or bodies** — a dashboard, a
ChatOps bot, a script reading `/api/status` — re-check its patterns before the
upgrade reaches production. The titles carry a new ` — <suffix>` and the body has
a fixed section order (failure cause, tests, artifacts, link to TeamCity).

### 4. Running builds now get stopped when their result has nowhere to go

`cancelObsolete.enabled` (on by default) stops a build **already running** when a
new commit is pushed to its pull request, or when the pull request is closed or
merged. It is what gives an agent back instead of producing a verdict nobody will
read, and the cancellation is published, so the commit gets an honest
`Build cancelled` row rather than an `in_progress` one that never resolves.

Never stopped: a **personal** build, or one somebody **started by hand**. On a
push, also spared: a build whose revision TeamCity has not resolved yet, and the
**last build in flight** for that branch.

Turn it off (or turn off the `queueCleanup.enabled` master switch it sits under)
if a pipeline of yours depends on a build finishing for a commit that has already
been superseded — a build that publishes an artifact from an intermediate commit
is the realistic case.

### Also worth knowing

- **Personal builds publish nothing** now — no `queued`, no `in_progress`, no
  conclusion, whatever `publishChecks` says. Before, triggering one by hand left
  a Check Run stuck on *"Queued"* for good, so this is unlikely to be a
  behaviour anybody built on. They still get their PR parameters and tags.
- **Sixteen published parameters instead of eight.** The new ones
  (`…pullRequest.url`, `.baseSha`, `.mergeBase`, `.changedFiles`, `.additions`,
  `.deletions`, `.commits`, `.labels`) are additions; nothing was renamed. A
  build queued **before** the upgrade has none of them, which is why the new
  *Pull request* tab renders without links on old builds instead of guessing a
  hostname.
- **`mergeBase.enabled` and `prTab.changedFiles`** (both on) add one
  `compare` call **per PR-info cache fill** — not per build. On a server close to
  its GitHub rate limit, untick them on the admin page.
- **`checkRun.annotationLogScan`** (on) reads the log of a **failed** build when
  its build problems carry no diagnostic. Bounded: it stops at the 50th
  annotation or after 200 000 lines. Untick it if reading big build logs is
  unwelcome on your server; the build-problem path keeps working.
- **`legacyAliases.enabled`**, if you use it, now also publishes the two branch
  names under the bundled feature's own dotted spelling
  (`teamcity.pullRequest.source.branch` / `.target.branch`) alongside the
  camelCase pair it published before. DSL written for the bundled `pullRequests`
  feature works unchanged now; nothing to undo.
- **If your App predates 1.9.0**, subscribe it to **`check_suite`** or the
  *Re-run all checks* button does nothing. **Verify App configuration** reports
  it as a missing event.

### The one knob that can break a merge queue

`teamcity.github.bridge.checkName.stripPrefix` (new, per project, on the
project's *GitHub Bridge* tab) shortens every Check Run name that project posts.
It is off unless you set it, and it is the only setting in this release that can
block pull requests:

> GitHub identifies a Check Run row by `(name, head_sha)`. Changing the name
> **starts a new row** and leaves the old ones where they are — so a branch
> protection rule that requires the old name waits for a check that will never
> arrive again, and every pull request sits on *"Required statuses must pass"*.

If you set it, update the protection rules (or rulesets) in the same change. The
build page's *Pull request* tab shows the exact string a rule must require, under
**Reports as**.

### Verifying the upgrade landed

| Check | Where | Expected |
|---|---|---|
| Version | `Administration -> Plugins List` | `1.10.0` |
| App configuration | admin page → **Verify App configuration** | no missing permission, no missing event (`pull_requests: read` is enough) |
| Self-tests | admin page → **Run self-tests** | the whole battery passes |
| The new tab | any PR build → **Pull request** | the pull request, its merge base, and *"What the bridge did"* |
| A real build | push to an open PR | the previous head's running builds are cancelled, the new head reports |

## Rolling back

Drop the previous zip back in and restart. The settings file needs no edit — the
older version ignores the keys it does not know
(`cancelObsolete.enabled`, `checkRun.infraNeutral`, `checkRun.testStats`,
`checkRun.timings`, `prTag.display`, `mergeBase.enabled`, `prTab.changedFiles`).

Two things do not roll back, and neither is harmful: Check Run rows already
posted on GitHub stay as they are, and PR tags already written to builds stay on
those builds.
