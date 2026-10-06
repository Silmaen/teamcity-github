<%--
  TeamCity GitHub Bridge - BuildFeature edit form.

  Renders inside the BuildType editor (Build Features tab) when an operator
  adds or edits a "GitHub Bridge integration" feature. The repository and
  connection live on the project's GitHub Bridge tab; these are the per-build-
  configuration options.

  Four sections — Triggers, Filters, On demand, Publication — each with a (?)
  link to its documentation. Fields most configurations never touch are
  `advancedSetting` rows, shown by TeamCity's "Show advanced options".

  Field names must match the PARAM_* constants in GitHubBridgeBuildFeature.kt.
  Validation messages come from getParametersProcessor and land in the
  `error_<name>` spans.
--%>
<%@ taglib prefix="props" tagdir="/WEB-INF/tags/props" %>
<%@ taglib prefix="l" tagdir="/WEB-INF/tags/layout" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ include file="../common/bridgeUi.jspf" %>

<jsp:useBean id="propertiesBean" scope="request" type="jetbrains.buildServer.controllers.BasePropertiesBean"/>
<c:set var="featureDoc" value="${bridgeDoc}configuration.md#4-per-buildtype-build-feature-github-bridge-integration"/>

<tr>
    <td colspan="2">
        <span class="bridge-note" style="font-size:12px;">
            Opts this build configuration into the bridge. Repository and connection are set on the
            project's <em>GitHub Bridge</em> tab.
        </span>
        <a class="bridge-doc" href="${featureDoc}" target="_blank" title="Documentation">?</a>
    </td>
</tr>

<%-- ===== Triggers ===== --%>
<tr class="groupingTitle">
    <td colspan="2">Triggers <a class="bridge-doc" href="${featureDoc}" target="_blank" title="Documentation">?</a></td>
</tr>
<tr>
    <th><label for="triggerOnBranch">Run on branches:</label></th>
    <td>
        <props:checkboxProperty name="triggerOnBranch"/>
        <label for="triggerOnBranch">Non-PR branches (<code>main</code>, <code>Release/*</code>…)</label>
    </td>
</tr>
<tr>
    <th><label for="triggerOnPrReady">Run on PR (ready):</label></th>
    <td>
        <props:checkboxProperty name="triggerOnPrReady"/>
        <label for="triggerOnPrReady">Part of the pull request's check set</label>
        <span class="smallNote">Unchecked: the bridge never starts it from a PR event and posts no "Skipped" row.</span>
    </td>
</tr>
<tr>
    <th><label for="triggerOnPrDraft">Run on PR (draft):</label></th>
    <td>
        <props:checkboxProperty name="triggerOnPrDraft"/>
        <label for="triggerOnPrDraft">Also on draft pull requests</label>
        <span class="smallNote">
            Off by default: a draft gets a "Skipped: draft PR" row. Needs "Run on PR (ready)". On a composite,
            it builds the whole chain.
        </span>
        <span class="error" id="error_triggerOnPrDraft"></span>
    </td>
</tr>
<tr class="advancedSetting">
    <th><label for="skipIfCommitPassed">Reuse a passed commit:</label></th>
    <td>
        <props:checkboxProperty name="skipIfCommitPassed"/>
        <label for="skipIfCommitPassed">Skip an automatic build of a commit that already passed here</label>
        <span class="smallNote">The earlier success is posted again. Leave off for scheduled suites.</span>
    </td>
</tr>

<%-- ===== Filters ===== --%>
<tr class="groupingTitle">
    <td colspan="2">Filters <a class="bridge-doc" href="${featureDoc}" target="_blank" title="Documentation">?</a></td>
</tr>
<tr>
    <td colspan="2"><span class="smallNote">
        Automatic pull-request builds only: a Run by hand always goes through. One rule per line,
        <code>+:pattern</code> / <code>-:pattern</code>.
    </span></td>
</tr>
<tr>
    <th><label for="pathFilter">Changed paths:</label></th>
    <td>
        <props:multilineProperty name="pathFilter" linkTitle="Edit changed-path filter" cols="58" rows="4"
                                 expanded="${not empty propertiesBean.properties['pathFilter']}"/>
        <span class="smallNote">Run only when a changed file matches, e.g. <code>+:src/api/**</code>. Empty: any change.</span>
        <span class="error" id="error_pathFilter"></span>
    </td>
</tr>
<tr>
    <th><label for="labelFilter">Labels:</label></th>
    <td>
        <props:multilineProperty name="labelFilter" linkTitle="Edit label filter" cols="58" rows="3"
                                 expanded="${not empty propertiesBean.properties['labelFilter']}"/>
        <span class="smallNote"><code>+:ci</code> runs only when labelled <code>ci</code>, <code>-:no-ci</code> skips. Empty: any labels.</span>
        <span class="error" id="error_labelFilter"></span>
    </td>
</tr>
<tr class="advancedSetting">
    <th><label for="requirePhrase">Require phrase:</label></th>
    <td>
        <props:textProperty name="requirePhrase" className="longField"/>
        <span class="smallNote">Run only when the PR title or description contains it.</span>
    </td>
</tr>
<tr class="advancedSetting">
    <th><label for="skipPhrase">Skip phrase:</label></th>
    <td>
        <props:textProperty name="skipPhrase" className="longField"/>
        <span class="smallNote">Skip when the PR title or description contains it, e.g. <code>[skip ci]</code>.</span>
    </td>
</tr>
<tr class="advancedSetting">
    <th><label for="prTriggerBranchesOverride">PR branches:</label></th>
    <td>
        <props:multilineProperty name="prTriggerBranchesOverride" linkTitle="Edit PR source branch list" cols="58" rows="4"
                                 expanded="${not empty propertiesBean.properties['prTriggerBranchesOverride']}"/>
        <span class="smallNote">Replaces the project's PR branch list, matched on the source branch (<code>Feature/x</code>).</span>
        <span class="error" id="error_prTriggerBranchesOverride"></span>
    </td>
</tr>
<tr class="advancedSetting">
    <th><label for="branchTriggerBranchesOverride">Non-PR branches:</label></th>
    <td>
        <props:multilineProperty name="branchTriggerBranchesOverride" linkTitle="Edit non-PR branch list" cols="58" rows="4"
                                 expanded="${not empty propertiesBean.properties['branchTriggerBranchesOverride']}"/>
        <span class="smallNote">Replaces the project's non-PR branch list.</span>
        <span class="error" id="error_branchTriggerBranchesOverride"></span>
    </td>
</tr>

<%-- ===== On demand ===== --%>
<tr class="groupingTitle">
    <td colspan="2">On demand <a class="bridge-doc" href="${featureDoc}" target="_blank" title="Documentation">?</a></td>
</tr>
<tr>
    <th><label for="runOnApproval">Run on approval:</label></th>
    <td>
        <props:checkboxProperty name="runOnApproval"/>
        <label for="runOnApproval">When a reviewer approves the pull request</label>
    </td>
</tr>
<tr>
    <th><label for="commentTrigger">Comment trigger:</label></th>
    <td>
        <props:textProperty name="commentTrigger" className="longField"/>
        <span class="smallNote">
            A phrase such as <code>/rebuild</code> in a PR comment by a trusted author runs it.
            Trusted authors are set server-wide. Empty: disabled.
        </span>
    </td>
</tr>

<%-- ===== Publication ===== --%>
<tr class="groupingTitle">
    <td colspan="2">Publication <a class="bridge-doc" href="${featureDoc}" target="_blank" title="Documentation">?</a></td>
</tr>
<tr>
    <th><label for="publishChecks">Publish to GitHub:</label></th>
    <td>
        <props:checkboxProperty name="publishChecks"/>
        <label for="publishChecks">Report every build of this configuration as a Check Run</label>
        <span class="smallNote">Whatever started the build. Unchecked: invisible on GitHub.</span>
    </td>
</tr>
<tr>
    <th><label for="checkName">Check name:</label></th>
    <td>
        <props:textProperty name="checkName" className="longField"/>
        <span class="smallNote">
            The row's name, verbatim. Empty: <code>TeamCity / &lt;project&gt; / &lt;configuration&gt;</code>,
            which changes when the configuration moves. Set it on a check a branch rule requires.
        </span>
        <span class="error" id="error_checkName"></span>
    </td>
</tr>
<tr class="advancedSetting">
    <th><label for="annotateDiff">Annotate the diff:</label></th>
    <td>
        <props:checkboxProperty name="annotateDiff"/>
        <label for="annotateDiff">Pin compiler errors and warnings to the pull request's diff</label>
        <span class="smallNote">The server and the project can also turn this off; any "off" wins.</span>
    </td>
</tr>
