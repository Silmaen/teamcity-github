<%--
  TeamCity GitHub Bridge - project settings tab.

  Renders under Administration -> <project> -> GitHub Bridge
  (Integrations group). Edits the six project-level parameters that
  configure the bridge for every opted-in BuildType in the project:
  repo, connectionId, and the two trigger-path toggles + branch lists.

  Posts to BridgeProjectSettingsController, which writes them as the
  project's own parameters and persists.
--%>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>

<style>
    .bridge-form th { text-align: left; vertical-align: top; padding: 8px 12px 8px 0; white-space: nowrap; }
    .bridge-form td { padding: 6px 0; }
    .bridge-form input[type=text] { width: 360px; }
    .bridge-form textarea { width: 360px; font-family: monospace; }
    .bridge-help { color: #666; font-size: 11px; margin-top: 4px; max-width: 520px; }
    .bridge-help code { background: #f5f5f5; padding: 1px 4px; border-radius: 2px; }
    .bridge-banner { padding: 8px 12px; border-radius: 3px; margin-bottom: 12px; max-width: 540px; }
    .bridge-banner.ok { background: #e6f4ea; border: 1px solid #b7dfc2; }
    .bridge-banner.bad { background: #fce8e6; border: 1px solid #f0b4ae; }
</style>

<c:if test="${not empty resultBanner}">
    <div class="bridge-banner ${resultBanner['level']}"><c:out value="${resultBanner['text']}"/></div>
</c:if>

<p class="bridge-help">
    These values apply to every build configuration in this project that
    carries the <em>GitHub Bridge integration</em> build feature
    (add it under a build configuration's <em>Build Features</em> tab).
    Sub-projects inherit these values unless they set their own.
    Per-build-configuration options (path filters, approval/comment
    triggers, branch-list overrides) live on the build feature itself.
</p>

<form method="post" action="${saveUrl}">
    <input type="hidden" name="projectExternalId" value="<c:out value='${projectExternalId}'/>"/>
    <input type="hidden" name="${csrfTokenName}" value="<c:out value='${csrfToken}'/>"/>

    <table class="bridge-form">
        <tr>
            <th><label for="repo">GitHub repository:</label></th>
            <td>
                <input type="text" id="repo" name="repo" value="<c:out value='${repo}'/>" placeholder="owner/name" required/>
                <div class="bridge-help">Required. Format <code>owner/name</code>, e.g. <code>acme/widgets</code>.</div>
            </td>
        </tr>
        <tr>
            <th><label for="connectionId">GitHub App connection ID:</label></th>
            <td>
                <input type="text" id="connectionId" name="connectionId" value="<c:out value='${connectionId}'/>" placeholder="managed or PROJECT_EXT_42" required/>
                <div class="bridge-help">
                    Required. Use <code>managed</code> to use the server-managed GitHub App
                    created from <em>Administration &rarr; GitHub Bridge</em> &mdash; <strong>or</strong>
                    the internal ID of a TeamCity GitHub App connection (e.g.
                    <code>PROJECT_EXT_42</code>, found under
                    <em>Administration &rarr; &lt;project&gt; &rarr; Connections</em>).
                </div>
            </td>
        </tr>
        <tr>
            <th><label for="branchTriggerEnabled">Trigger on non-PR branches:</label></th>
            <td>
                <input type="checkbox" id="branchTriggerEnabled" name="branchTriggerEnabled" <c:if test="${branchTriggerEnabled}">checked</c:if>/>
                <div class="bridge-help">When off, the bridge never triggers builds on non-PR branches for this project.</div>
            </td>
        </tr>
        <tr>
            <th><label for="branchTriggerBranches">Non-PR branch filter:</label></th>
            <td>
                <textarea id="branchTriggerBranches" name="branchTriggerBranches" rows="3"><c:out value="${branchTriggerBranches}"/></textarea>
                <div class="bridge-help">
                    Optional. VCS branch-filter syntax (<code>+:pattern</code> / <code>-:pattern</code>
                    per line, <code>/regex/</code> for Java regex). Empty = match every branch.
                </div>
            </td>
        </tr>
        <tr>
            <th><label for="prTriggerEnabled">Trigger on pull requests:</label></th>
            <td>
                <input type="checkbox" id="prTriggerEnabled" name="prTriggerEnabled" <c:if test="${prTriggerEnabled}">checked</c:if>/>
                <div class="bridge-help">When off, the bridge never triggers builds for pull requests in this project.</div>
            </td>
        </tr>
        <tr>
            <th><label for="prTriggerBranches">PR source-branch filter:</label></th>
            <td>
                <textarea id="prTriggerBranches" name="prTriggerBranches" rows="3"><c:out value="${prTriggerBranches}"/></textarea>
                <div class="bridge-help">
                    Optional. Matched against the PR's source branch name (e.g.
                    <code>Feature/foo</code>), not the <code>pull/N</code> literal. Empty = match every PR.
                </div>
            </td>
        </tr>
        <tr>
            <th><label for="prBuildRefBranch">Build PRs on their own branch:</label></th>
            <td>
                <input type="checkbox" id="prBuildRefBranch" name="prBuildRefBranch" <c:if test="${prBuildRefBranch}">checked</c:if>/>
                <div class="bridge-help">
                    Off (default): a PR build runs on the synthetic <code>pull/N</code> ref.<br/>
                    On: it runs on the PR's own head branch (e.g. <code>Feature/foo</code>) &mdash; readable in every
                    TeamCity screen, and a push builds <em>once</em> instead of twice once a PR exists.
                    Requires the head branches to be in the VCS root's branch spec
                    (e.g. <code>+:refs/heads/Feature/*</code>); pull requests from forks are ignored either way.
                </div>
            </td>
        </tr>
        <tr>
            <th><label for="annotationsEnabled">Annotate the diff:</label></th>
            <td>
                <input type="checkbox" id="annotationsEnabled" name="annotationsEnabled" <c:if test="${annotationsEnabled}">checked</c:if>/>
                <label for="annotationsEnabled" style="font-weight:normal;">
                    This project's builds may pin compiler errors and warnings to the pull request's diff.
                </label>
                <div class="bridge-help">
                    Three levels can each say no &mdash; the server (<em>Administration &rarr; GitHub Bridge</em>),
                    this project chain, and each build configuration's <em>GitHub Bridge integration</em>
                    feature &mdash; and <strong>one &quot;no&quot; anywhere wins</strong>. Unticking it here holds
                    annotations off for this project <em>and every project under it</em>: a sub-project
                    ticking its own box does not take that back. Everything else the bridge reports
                    (status, timings, tests, artifacts) is unaffected.
                    <c:if test="${not empty annotationsVetoedBy}">
                        <div style="margin-top:.5em;padding:.4em .6em;background:#fff4e5;border-left:3px solid #e8a33d;color:#663c00;">
                            <strong>Already off above.</strong> The parent project
                            <strong><c:out value="${annotationsVetoedBy}"/></strong> has annotations
                            turned off, so no build in this project annotates a diff whatever this
                            checkbox says. Remove the &quot;no&quot; there to change that.
                        </div>
                    </c:if>
                </div>
            </td>
        </tr>
        <tr>
            <td></td>
            <td><input type="submit" class="btn btn_primary" value="Save GitHub Bridge settings"/></td>
        </tr>
    </table>
</form>
