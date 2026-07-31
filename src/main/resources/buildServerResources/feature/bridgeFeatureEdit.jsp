<%--
  TeamCity GitHub Bridge - BuildFeature edit form.

  Renders inside the BuildType editor (Build Features tab) when an
  operator adds or edits a "GitHub Bridge integration" feature.

  The feature exposes per-task trigger gates (HARD) and per-task
  branch list overrides (SOFT, bypassed by manual triggers). The
  mandatory configuration (repo, connection ID, project-level
  toggles, default branch lists) lives at the project level as
  standard project parameters; see doc/configuration.md.

  Field names must match the PARAM_* constants in
  GitHubBridgeBuildFeature.kt. Validation messages come from
  getParametersProcessor and are rendered by the surrounding TC
  form via the `propertiesBean` model attribute.
--%>
<%@ taglib prefix="props" tagdir="/WEB-INF/tags/props" %>
<%@ taglib prefix="l" tagdir="/WEB-INF/tags/layout" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>

<jsp:useBean id="propertiesBean" scope="request" type="jetbrains.buildServer.controllers.BasePropertiesBean"/>

<style>
    .bridge-feature-help { color: #666; font-size: 11px; margin-top: 4px; }
    .bridge-feature-help code { background: #f5f5f5; padding: 1px 4px; border-radius: 2px; font-size: 11px; }
    .bridge-feature-help ul { margin: 4px 0 4px 18px; }
</style>

<tr>
    <th colspan="2" style="background:#f5f5f5; padding:8px 12px;">
        <strong>GitHub Bridge integration</strong>
        <div class="bridge-feature-help" style="font-weight:normal; margin-top:4px;">
            Opts this build configuration into the plugin's trigger paths,
            draft suppression, and Check Run lifecycle. The repository,
            connection, and default branch filters are set on the project's
            <em>GitHub Bridge</em> tab
            (<em>Administration &rarr; &lt;project&gt; &rarr; GitHub Bridge</em>);
            the fields below are per-build-configuration options.
            <br/><br/>
            <strong>Hard</strong> gates block even a manual &ldquo;Run&rdquo;
            click; <strong>soft</strong> branch/path filters are bypassed by a
            manual operator trigger.
        </div>
    </th>
</tr>

<tr>
    <th><label for="triggerOnBranch">Run on non-PR branches:</label></th>
    <td>
        <props:checkboxProperty name="triggerOnBranch"/>
        <label for="triggerOnBranch" style="font-weight:normal;">
            This BuildType runs on TeamCity-side branch triggers (main,
            Release/*, etc.).
        </label>
        <div class="bridge-feature-help">
            When unchecked, the bridge never triggers this build configuration
            on a non-PR branch and never removes such a build either &mdash; a Run,
            a schedule or a VCS trigger still works, and still reports.
            Defaults to checked.
        </div>
    </td>
</tr>

<tr>
    <th><label for="triggerOnPrReady">Run on PR (ready):</label></th>
    <td>
        <props:checkboxProperty name="triggerOnPrReady"/>
        <label for="triggerOnPrReady" style="font-weight:normal;">
            The listener enqueues this BuildType when a PR is ready (or
            transitions from draft to ready).
        </label>
        <div class="bridge-feature-help">
            When unchecked, this build configuration is not part of the PR
            check set: the bridge never enqueues it from a PR event and posts
            no "Skipped" row for it. An explicit Run, a schedule or a GitHub
            command still works &mdash; and still reports. Defaults to checked.
        </div>
    </td>
</tr>

<tr>
    <th><label for="publishChecks">Publish to GitHub:</label></th>
    <td>
        <props:checkboxProperty name="publishChecks"/>
        <label for="publishChecks" style="font-weight:normal;">
            This build configuration reports its status to GitHub.
        </label>
        <div class="bridge-feature-help">
            The only thing publication depends on &mdash; deliberately independent of
            what started the build. Checked: every build reports (PR event, VCS
            trigger, schedule, manual Run, GitHub command alike). Unchecked:
            this build configuration is invisible on GitHub, whatever happens,
            while still benefiting from the PR parameters and tags.
            Defaults to checked.
        </div>
    </td>
</tr>

<tr>
    <th><label for="annotateDiff">Annotate the diff:</label></th>
    <td>
        <props:checkboxProperty name="annotateDiff"/>
        <label for="annotateDiff" style="font-weight:normal;">
            This build configuration may pin its compiler errors and warnings to
            the pull request's diff.
        </label>
        <div class="bridge-feature-help">
            Three levels can each say no &mdash; the server (<em>Administration &rarr;
            GitHub Bridge</em>), the project chain (<em>&lt;project&gt; &rarr;
            GitHub Bridge</em>) and this checkbox &mdash; and <strong>one "no" anywhere
            wins</strong>: leaving this ticked does not overrule a project or a
            server that turned annotations off. Untick it for a configuration
            whose diagnostics are noise on a review (a nightly warning sweep, a
            legacy target) while keeping them for the rest. Everything else this
            configuration reports &mdash; status, timings, tests, artifacts &mdash; is
            unaffected. Defaults to checked.
        </div>
    </td>
</tr>

<tr>
    <th><label for="skipIfCommitPassed">Reuse a passed commit:</label></th>
    <td>
        <props:checkboxProperty name="skipIfCommitPassed"/>
        <label for="skipIfCommitPassed" style="font-weight:normal;">
            Drop an automatic build when this commit already passed here, and
            republish that success.
        </label>
        <div class="bridge-feature-help">
            The queued build is removed and the earlier success is re-posted at
            the same commit (GitHub keys a Check Run on name + commit, so it is
            the same row). Applies to <b>automatic</b> triggers only: a manual
            Run, a GitHub command and the Re-run buttons mean "do it again".
            Leave it <b>off</b> for scheduled suites &mdash; a nightly is expected to
            re-run on an unchanged commit. Defaults to unchecked.
        </div>
    </td>
</tr>

<tr>
    <th><label for="triggerOnPrDraft">Run on PR (draft):</label></th>
    <td>
        <props:checkboxProperty name="triggerOnPrDraft"/>
        <label for="triggerOnPrDraft" style="font-weight:normal;">
            The listener also enqueues this BuildType on draft PR events.
        </label>
        <div class="bridge-feature-help">
            When unchecked, an automatic build for a draft PR is dropped and
            a "Skipped: draft PR" row is posted instead. An explicit Run or
            GitHub command on a draft still runs. Defaults to checked, and
            requires "Run on PR (ready)".
        </div>
        <span class="error" id="error_triggerOnPrDraft"></span>
    </td>
</tr>

<tr>
    <th><label for="branchTriggerBranchesOverride">Branches list override (non-PR):</label></th>
    <td>
        <props:multilineProperty name="branchTriggerBranchesOverride"
                                 linkTitle="Edit non-PR branch list"
                                 cols="58" rows="4"
                                 expanded="${not empty propertiesBean.properties['branchTriggerBranchesOverride']}"/>
        <div class="bridge-feature-help">
            Optional. When set, REPLACES the project's
            <code>teamcity.github.bridge.branchTrigger.branches</code> for
            this BuildType. Same syntax as TC's VCS branch filters
            (<code>+:pattern</code> / <code>-:pattern</code> per line,
            <code>/regex/</code> for explicit Java regex). Empty = inherit
            project's list.
            <br>
            SOFT: a manual operator trigger on a branch outside the list
            still runs. Auto builds on excluded branches are suppressed
            silently (no GitHub Check Run posted for non-PR contexts).
        </div>
        <span class="error" id="error_branchTriggerBranchesOverride"></span>
    </td>
</tr>

<tr>
    <th><label for="prTriggerBranchesOverride">Branches list override (PR source):</label></th>
    <td>
        <props:multilineProperty name="prTriggerBranchesOverride"
                                 linkTitle="Edit PR source branch list"
                                 cols="58" rows="4"
                                 expanded="${not empty propertiesBean.properties['prTriggerBranchesOverride']}"/>
        <div class="bridge-feature-help">
            Optional. When set, REPLACES the project's
            <code>teamcity.github.bridge.prTrigger.branches</code> for
            this BuildType. Matched against the PR's source branch name
            (e.g. <code>Feature/foo</code>), not the <code>pull/N</code>
            literal. Empty = inherit project's list.
            <br>
            SOFT: a manual operator trigger on an excluded PR still runs.
            Auto enqueues for excluded PRs post a
            <em>"Skipped: branch out of scope"</em> Check Run on GitHub.
        </div>
        <span class="error" id="error_prTriggerBranchesOverride"></span>
    </td>
</tr>

<tr>
    <th><label for="pathFilter">Changed-path filter (monorepo):</label></th>
    <td>
        <props:multilineProperty name="pathFilter"
                                 linkTitle="Edit changed-path filter"
                                 cols="58" rows="4"
                                 expanded="${not empty propertiesBean.properties['pathFilter']}"/>
        <div class="bridge-feature-help">
            Optional. When set, the listener only enqueues this BuildType for
            a PR if at least one of the PR's changed files matches. Same
            syntax as the branch filters (<code>+:src/api/**</code> /
            <code>-:docs/*</code> per line; <code>*</code> spans
            <code>/</code>). Empty = run regardless of which paths changed.
            <br>
            Enforced only for PR webhook triggers (it needs the PR's file
            list from GitHub). A non-matching PR gets a
            <em>"Skipped: paths out of scope"</em> Check Run.
        </div>
        <span class="error" id="error_pathFilter"></span>
    </td>
</tr>

<tr>
    <th><label for="runOnApproval">Run on PR approval:</label></th>
    <td>
        <props:checkboxProperty name="runOnApproval"/>
        <label for="runOnApproval" style="font-weight:normal;">
            Enqueue this BuildType when a reviewer approves the PR
            (<code>pull_request_review</code>).
        </label>
        <div class="bridge-feature-help">
            For expensive suites you only want to run after review. Independent
            of the ready/synchronize triggers above. Requires the GitHub App to
            send <code>pull_request_review</code> events. Defaults to unchecked.
        </div>
    </td>
</tr>

<tr>
    <th><label for="commentTrigger">PR comment trigger phrase:</label></th>
    <td>
        <props:textProperty name="commentTrigger" className="longField"/>
        <label for="commentTrigger" style="font-weight:normal;">
            e.g. <code>/rebuild</code>
        </label>
        <div class="bridge-feature-help">
            Optional. When a comment on the pull request contains this phrase
            (case-insensitive) and the commenter is trusted, this build
            configuration is enqueued. Fires on
            <code>pull_request_review_comment</code> (inline diff comments) &mdash;
            the managed App subscribes to it by default. General PR conversation
            comments (<code>issue_comment</code>) also work, but GitHub only
            exposes that event if the App is granted the <em>Issues</em>
            permission, so it is off by default. Trusted authors are controlled
            server-side (Administration &rarr; GitHub Bridge);
            <code>OWNER,MEMBER,COLLABORATOR</code> by default. Empty = disabled.
        </div>
    </td>
</tr>

<tr>
    <th colspan="2" style="background:#f5f5f5; padding:6px 12px;">
        <strong>PR metadata gate</strong>
        <div class="bridge-feature-help" style="font-weight:normal; margin-top:2px;">
            Optional SOFT filters on the pull request's title, description and
            labels. Auto triggers that don't pass post a
            <em>"Skipped: PR metadata out of scope"</em> Check Run; a manual
            "Run" always bypasses them.
        </div>
    </th>
</tr>
<tr>
    <th><label for="requirePhrase">Require phrase (title/body):</label></th>
    <td>
        <props:textProperty name="requirePhrase" className="longField"/>
        <div class="bridge-feature-help">
            Optional. Run only if the PR <strong>title or description</strong>
            contains this text (case-insensitive). Empty = no requirement.
        </div>
    </td>
</tr>
<tr>
    <th><label for="skipPhrase">Skip phrase (title/body):</label></th>
    <td>
        <props:textProperty name="skipPhrase" className="longField"/>
        <div class="bridge-feature-help">
            Optional. <strong>Skip</strong> the build if the PR title or
            description contains this text &mdash; e.g. <code>[skip ci]</code>.
        </div>
    </td>
</tr>
<tr>
    <th><label for="labelFilter">Label filter:</label></th>
    <td>
        <props:multilineProperty name="labelFilter"
                                 linkTitle="Edit label filter"
                                 cols="58" rows="3"
                                 expanded="${not empty propertiesBean.properties['labelFilter']}"/>
        <div class="bridge-feature-help">
            Optional. VCS-filter syntax over the PR's <strong>labels</strong>:
            <code>+:ci</code> (run only if labelled <code>ci</code>),
            <code>-:no-ci</code> (skip if labelled <code>no-ci</code>),
            one rule per line. Empty = run regardless of labels.
        </div>
        <span class="error" id="error_labelFilter"></span>
    </td>
</tr>
