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
            HARD: when unchecked, even an operator clicking "Run" on a
            non-PR branch is blocked. Defaults to checked.
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
            HARD: when unchecked, even manual triggers on PR branches are
            blocked. Defaults to checked.
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
            HARD: when unchecked AND PR is draft, the listener posts a
            <em>"Skipped: draft PR"</em> Check Run on GitHub; manual triggers
            on a draft PR are blocked. Requires <em>Run on PR (ready)</em>
            to be checked. Defaults to checked.
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
            Optional. When a PR comment contains this phrase (case-insensitive)
            and the commenter is trusted, this BuildType is enqueued. Requires
            the GitHub App to send <code>issue_comment</code> events. Trusted
            authors are controlled server-side (Administration &rarr; GitHub
            Bridge); collaborators only, by default. Empty = disabled.
        </div>
    </td>
</tr>
