<%--
  TeamCity GitHub Bridge - project settings tab.

  Renders under Administration -> <project> -> GitHub Bridge (Integrations
  group). One form over four tabs — Repository, Triggers, Reporting, Pull
  requests — posted to BridgeProjectSettingsController, which writes the
  project's own parameters and redirects back to the tab the user was on.

  These apply to every build configuration of the project (and its
  sub-projects) that carries the GitHub Bridge integration feature.
--%>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ include file="../common/bridgeUi.jspf" %>
<c:set var="projectDoc" value="${bridgeDoc}configuration.md#3-per-project-parameters"/>

<c:if test="${not empty resultBanner}">
    <div class="bridge-banner ${resultBanner['level']}"><c:out value="${resultBanner['text']}"/></div>
</c:if>

<p class="bridge-intro">
    Applies to every build configuration of this project, and of its sub-projects, that carries the
    <em>GitHub Bridge integration</em> build feature. A sub-project can override any value.
    <a class="bridge-doc" href="${projectDoc}" target="_blank" title="Documentation">?</a>
</p>

<div class="bridge-tabs">
    <a href="#bridge-tab-repository" data-tab="repository">Repository</a>
    <a href="#bridge-tab-triggers" data-tab="triggers">Triggers</a>
    <a href="#bridge-tab-reporting" data-tab="reporting">Reporting</a>
    <a href="#bridge-tab-pullrequests" data-tab="pullrequests">Pull requests
        <c:if test="${not empty labelRuleErrors}"><span class="bridge-badge">!</span></c:if></a>
</div>

<form method="post" action="${saveUrl}" data-bridge-keep-tab>
    <input type="hidden" name="projectExternalId" value="<c:out value='${projectExternalId}'/>"/>
    <input type="hidden" name="${csrfTokenName}" value="<c:out value='${csrfToken}'/>"/>
    <input type="hidden" name="bridgeTab" value="repository"/>

    <%-- ===== Repository ===== --%>
    <div class="bridge-panel" id="bridge-tab-repository">
        <table class="bridge-form">
            <tr>
                <th><label for="repo">GitHub repository:</label></th>
                <td>
                    <input type="text" id="repo" name="repo" value="<c:out value='${repo}'/>" placeholder="owner/name"/>
                    <div class="bridge-note">Required, e.g. <code>acme/widgets</code>.</div>
                </td>
            </tr>
            <tr>
                <th><label for="connectionId">GitHub App connection ID:</label></th>
                <td>
                    <input type="text" id="connectionId" name="connectionId" value="<c:out value='${connectionId}'/>" placeholder="managed"/>
                    <div class="bridge-note">
                        Required. <code>managed</code> for the App created under <em>Administration &rarr; GitHub Bridge</em>,
                        or a TeamCity GitHub App connection ID such as <code>PROJECT_EXT_42</code>.
                        <a class="bridge-doc" href="${bridgeDoc}github-app-setup.md" target="_blank" title="Documentation">?</a>
                    </div>
                </td>
            </tr>
            <tr>
                <th><label for="prBuildRefBranch">Build PRs on their own branch:</label></th>
                <td>
                    <input type="checkbox" id="prBuildRefBranch" name="prBuildRefBranch" <c:if test="${prBuildRefBranch}">checked</c:if>/>
                    <label for="prBuildRefBranch">Run a PR build on its head branch (<code>Feature/x</code>), not <code>pull/N</code></label>
                    <div class="bridge-note">
                        Readable everywhere in TeamCity, and one build per push. The head branches must be in the
                        VCS root's branch spec.
                    </div>
                </td>
            </tr>
        </table>
    </div>

    <%-- ===== Triggers ===== --%>
    <div class="bridge-panel" id="bridge-tab-triggers">
        <p class="bridge-intro">
            Which builds the bridge may start for this project. Each build configuration can narrow this further
            in its feature. Rules: one per line, <code>+:pattern</code> / <code>-:pattern</code>; empty matches all.
            <a class="bridge-doc" href="${projectDoc}" target="_blank" title="Documentation">?</a>
        </p>
        <table class="bridge-form">
            <tr>
                <th><label for="prTriggerEnabled">Pull requests:</label></th>
                <td>
                    <input type="checkbox" id="prTriggerEnabled" name="prTriggerEnabled" <c:if test="${prTriggerEnabled}">checked</c:if>/>
                    <label for="prTriggerEnabled">Start builds for pull requests</label>
                </td>
            </tr>
            <tr>
                <th><label for="prTriggerBranches">PR source branches:</label></th>
                <td>
                    <textarea id="prTriggerBranches" name="prTriggerBranches" rows="3" placeholder="+:Feature/*"><c:out value="${prTriggerBranches}"/></textarea>
                    <div class="bridge-note">Matched on the PR's source branch, not on <code>pull/N</code>.</div>
                </td>
            </tr>
            <tr>
                <th><label for="branchTriggerEnabled">Other branches:</label></th>
                <td>
                    <input type="checkbox" id="branchTriggerEnabled" name="branchTriggerEnabled" <c:if test="${branchTriggerEnabled}">checked</c:if>/>
                    <label for="branchTriggerEnabled">Start builds on non-PR branches</label>
                </td>
            </tr>
            <tr>
                <th><label for="branchTriggerBranches">Non-PR branches:</label></th>
                <td>
                    <textarea id="branchTriggerBranches" name="branchTriggerBranches" rows="3" placeholder="+:main"><c:out value="${branchTriggerBranches}"/></textarea>
                </td>
            </tr>
        </table>
    </div>

    <%-- ===== Reporting ===== --%>
    <div class="bridge-panel" id="bridge-tab-reporting">
        <table class="bridge-form">
            <tr>
                <th><label for="checkNameStripPrefix">Strip this prefix from Check Run names:</label></th>
                <td>
                    <input type="text" id="checkNameStripPrefix" name="checkNameStripPrefix"
                           value="<c:out value='${checkNameStripPrefix}'/>" placeholder="TeamCity / MyProject / PR /"/>
                    <div class="bridge-note">
                        Shortens <code>TeamCity / &lt;project path&gt; / &lt;configuration&gt;</code>, which GitHub truncates
                        at the end. Ignored when it does not match.
                    </div>
                    <div class="bridge-banner warn" style="margin-top:6px;">
                        <strong>This renames the checks.</strong> A branch protection rule requiring an old name
                        then waits for ever: update the rules in the same change, or give required checks a fixed
                        <em>Check name</em> in their feature.
                    </div>
                </td>
            </tr>
            <tr>
                <th><label for="annotationsEnabled">Annotate the diff:</label></th>
                <td>
                    <input type="checkbox" id="annotationsEnabled" name="annotationsEnabled" <c:if test="${annotationsEnabled}">checked</c:if>/>
                    <label for="annotationsEnabled">Builds may pin compiler errors and warnings to the pull request's diff</label>
                    <div class="bridge-note">Off here holds it off for every sub-project too; the server and each feature can also turn it off.</div>
                    <c:if test="${not empty annotationsVetoedBy}">
                        <div class="bridge-banner warn" style="margin-top:6px;">
                            <strong>Already off above:</strong> the parent project
                            <strong><c:out value="${annotationsVetoedBy}"/></strong> turned annotations off, whatever this says.
                        </div>
                    </c:if>
                </td>
            </tr>
        </table>
    </div>

    <%-- ===== Pull requests ===== --%>
    <div class="bridge-panel" id="bridge-tab-pullrequests">
        <p class="bridge-intro">
            Both write to the pull request: the App needs the <strong>Issues: write</strong> permission.
            <a class="bridge-doc" href="${bridgeDoc}github-app-setup.md" target="_blank" title="Permissions">?</a>
        </p>
        <table class="bridge-form">
            <tr>
                <th><label for="autoAssignAuthor">Assign to the author:</label></th>
                <td>
                    <input type="checkbox" id="autoAssignAuthor" name="autoAssignAuthor" <c:if test="${autoAssignAuthor}">checked</c:if>/>
                    <label for="autoAssignAuthor">A pull request opened with nobody assigned goes to its author</label>
                    <div class="bridge-note">Never replaces an assignee, never for a bot. Unticked still inherits a parent's "on".</div>
                </td>
            </tr>
            <tr>
                <th><label for="labelRules">Label rules:</label></th>
                <td>
                    <textarea id="labelRules" name="labelRules" rows="6"
                              placeholder="network <= paths +:src/net/**&#10;team: core <= author @acme/core&#10;docs <= paths +:docs/** ; title ^docs"><c:out value="${labelRules}"/></textarea>
                    <c:if test="${not empty labelRuleErrors}">
                        <div class="bridge-banner bad" style="margin-top:6px;">
                            These lines are ignored:
                            <ul><c:forEach items="${labelRuleErrors}" var="e"><li><c:out value="${e}"/></li></c:forEach></ul>
                        </div>
                    </c:if>
                    <div class="bridge-note">
                        <code>&lt;label&gt; &lt;= &lt;condition&gt; ; &lt;condition&gt;</code>, one rule per line, all conditions required:
                        <code>paths</code>, <code>author</code> (logins, <code>@org/team</code>), <code>base</code>, <code>head</code>,
                        <code>title</code> (regex). Labels are only added; one removed by hand stays removed.
                        <a class="bridge-doc" href="${bridgeDoc}configuration.md#label-rules" target="_blank" title="Documentation">?</a>
                    </div>
                </td>
            </tr>
        </table>
    </div>

    <div class="bridge-actions">
        <input type="submit" class="btn btn_primary" value="Save"/>
    </div>
</form>
<script type="text/javascript">BridgeTabs.init('repository');</script>
