<%--
  TeamCity GitHub Bridge - "Pull request" build tab.

  Renders on the build page (PlaceId.BUILD_RESULTS_TAB) for a build that
  belongs to a pull request. The tab is hidden entirely otherwise, so this
  page never has to say "no pull request".

  Two halves:
    - what the pull request IS, from the parameters this build carries;
    - what the BRIDGE did with it (reported or not, under which Check Run
      name, what started the build).

  No GitHub call is made to render this page, and every outbound link is
  derived from the pull request's own URL - so they all appear together, or
  none of them does, and never one built from a guessed hostname.

  Non-ASCII characters are written as HTML entities on purpose: TeamCity
  serves these fragments as ISO-8859-1, and a raw UTF-8 dash comes out as
  mojibake. Inside <style> blocks, where entities are not decoded, use ASCII.
--%>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>

<%--
  Icons are inline SVG: nothing to serve, nothing for a content policy to block,
  and `fill="currentColor"` makes them follow the surrounding text colour, so they
  work in the light and the dark theme without a second asset.
  Paths are GitHub's own Octicons (MIT).
--%>
<c:set var="ghMark">
    <svg viewBox="0 0 16 16" width="15" height="15" aria-hidden="true" focusable="false"
         style="vertical-align:-2px; fill:currentColor;">
        <path d="M8 0c4.42 0 8 3.58 8 8a8.013 8.013 0 0 1-5.45 7.59c-.4.08-.55-.17-.55-.38 0-.27.01-1.13.01-2.2 0-.75-.25-1.23-.54-1.48 1.78-.2 3.65-.88 3.65-3.95 0-.88-.31-1.59-.82-2.15.08-.2.36-1.02-.08-2.12 0 0-.66-.21-2.2.82-.6-.17-1.23-.25-1.87-.25-.64 0-1.27.08-1.87.25-1.54-1.02-2.2-.82-2.2-.82-.44 1.1-.16 1.92-.08 2.12-.51.56-.82 1.28-.82 2.15 0 3.06 1.86 3.75 3.64 3.95-.23.2-.44.55-.51 1.07-.46.21-1.61.55-2.33-.66-.15-.24-.6-.83-1.23-.82-.67.01-.27.38.01.53.34.19.73.9.82 1.13.16.45.68 1.31 2.69.94 0 .67.01 1.3.01 1.49 0 .21-.15.45-.55.38A7.995 7.995 0 0 1 0 8c0-4.42 3.58-8 8-8Z"></path>
    </svg>
</c:set>
<c:set var="extMark">
    <svg viewBox="0 0 16 16" width="11" height="11" aria-hidden="true" focusable="false"
         style="vertical-align:0; fill:currentColor; opacity:.55;">
        <path d="M3.75 2h3.5a.75.75 0 0 1 0 1.5h-3.5a.25.25 0 0 0-.25.25v8.5c0 .138.112.25.25.25h8.5a.25.25 0 0 0 .25-.25v-3.5a.75.75 0 0 1 1.5 0v3.5A1.75 1.75 0 0 1 12.25 14h-8.5A1.75 1.75 0 0 1 2 12.25v-8.5C2 2.784 2.784 2 3.75 2Zm6.854-1h4.146a.25.25 0 0 1 .25.25v4.146a.25.25 0 0 1-.427.177L13.03 4.03 9.28 7.78a.751.751 0 0 1-1.042-.018.751.751 0 0 1-.018-1.042l3.75-3.75-1.543-1.543A.25.25 0 0 1 10.604 1Z"></path>
    </svg>
</c:set>

<jsp:useBean id="pr" scope="request" type="io.github.dlachouette.teamcity.github.web.PrTabModel"/>

<style>
    .bridge-pr { margin: 12px 0 24px; max-width: 980px; }
    .bridge-pr h2 { font-size: 18px; margin: 0 0 2px; font-weight: normal; }
    .bridge-pr h3 { font-size: 12px; text-transform: uppercase; letter-spacing: .04em;
        color: #888; margin: 22px 0 6px; font-weight: 600; }
    .bridge-pr .bridge-pr-state { font-size: 11px; font-weight: 600; padding: 1px 8px;
        border-radius: 10px; border: 1px solid; vertical-align: middle; margin-left: 6px; }
    .bridge-pr .bridge-pr-draft { background: #e6ebef; color: #37474f; border-color: #aebfc9; }
    .bridge-pr .bridge-pr-ready { background: #e4f5e6; color: #17601f; border-color: #91cf9a; }
    .bridge-pr table { border-collapse: collapse; }
    .bridge-pr th { text-align: left; vertical-align: top; padding: 5px 16px 5px 0;
        white-space: nowrap; font-weight: normal; color: #666; }
    .bridge-pr td { padding: 5px 0; vertical-align: top; }
    .bridge-pr code { background: #f5f5f5; padding: 1px 5px; border-radius: 2px; }
    .bridge-pr .bridge-pr-help { color: #666; font-size: 11px; margin-top: 3px; }
    .bridge-pr .bridge-pr-label { display: inline-block; font-size: 11px; padding: 1px 7px;
        margin: 0 4px 3px 0; border-radius: 10px; border: 1px solid #c9c9c9; background: #f0f0f0; }
    .bridge-pr .bridge-pr-links a { margin-right: 14px; white-space: nowrap; }
    .bridge-pr .bridge-pr-note { border-left: 3px solid #e8a33d; background: #fff8ee;
        padding: 6px 10px; margin: 10px 0; font-size: 12px; }
    /* Two columns of paths: a 40-file change is a wall in one. */
    .bridge-pr .bridge-pr-files { columns: 2 380px; column-gap: 28px; margin: 0; padding-left: 18px;
        font-family: monospace; font-size: 12px; line-height: 1.55; }
    .bridge-pr .bridge-pr-files li { break-inside: avoid; }
</style>

<div class="bridge-pr">

    <%-- ---------- what the pull request is ---------- --%>

    <h2>
        <c:out value="${ghMark}" escapeXml="false"/>
        <c:choose>
            <c:when test="${not empty pr.url}">
                <a href="<c:out value='${pr.url}'/>" target="_blank" rel="noopener noreferrer">
                    <c:out value="${empty pr.title ? 'Pull request' : pr.title}"/> #${pr.number}
                </a>
            </c:when>
            <c:otherwise>
                <c:out value="${empty pr.title ? 'Pull request' : pr.title}"/> #${pr.number}
            </c:otherwise>
        </c:choose>
        <span class="bridge-pr-state ${pr.draft ? 'bridge-pr-draft' : 'bridge-pr-ready'}">${pr.draft ? 'draft' : 'ready'}</span>
    </h2>

    <c:if test="${empty pr.url}">
        <div class="bridge-pr-note">
            <strong>No link, and no derived links.</strong> This build ran before the
            plugin published the pull request's URL
            (<code>teamcity.github.bridge.pullRequest.url</code>, new in 1.10.0), and
            the plugin will not build a GitHub URL out of a guessed hostname &mdash; a
            link to the wrong server is worse than none. Any build queued after the
            upgrade carries it.
        </div>
    </c:if>

    <c:if test="${not empty pr.url}">
        <div class="bridge-pr-links" style="margin-top:8px;">
            <a href="<c:out value='${pr.url}'/>" target="_blank" rel="noopener noreferrer">Pull request <c:out value="${extMark}" escapeXml="false"/></a>
            <a href="<c:out value='${pr.checksUrl}'/>" target="_blank" rel="noopener noreferrer">Checks <c:out value="${extMark}" escapeXml="false"/></a>
            <a href="<c:out value='${pr.filesUrl}'/>" target="_blank" rel="noopener noreferrer">Files changed <c:out value="${extMark}" escapeXml="false"/></a>
            <a href="<c:out value='${pr.commitsUrl}'/>" target="_blank" rel="noopener noreferrer">Commits <c:out value="${extMark}" escapeXml="false"/></a>
            <c:if test="${not empty pr.compareUrl}">
                <a href="<c:out value='${pr.compareUrl}'/>" target="_blank" rel="noopener noreferrer">This change only <c:out value="${extMark}" escapeXml="false"/></a>
            </c:if>
        </div>
    </c:if>

    <table>
        <c:if test="${not empty pr.author}">
            <tr><th>Author</th><td><c:out value="${pr.author}"/></td></tr>
        </c:if>

        <tr>
            <th>Merging</th>
            <td>
                <code><c:out value="${empty pr.sourceBranch ? '(unknown)' : pr.sourceBranch}"/></code>
                &rarr;
                <code><c:out value="${empty pr.targetBranch ? '(unknown)' : pr.targetBranch}"/></code>
            </td>
        </tr>

        <tr>
            <th>Head commit</th>
            <td>
                <c:choose>
                    <c:when test="${not empty pr.headCommitUrl}">
                        <a href="<c:out value='${pr.headCommitUrl}'/>" target="_blank" rel="noopener noreferrer">
                            <code><c:out value="${pr.headSha}"/></code>
                        </a>
                    </c:when>
                    <c:otherwise>
                        <code><c:out value="${empty pr.headSha ? '(unknown)' : pr.headSha}"/></code>
                    </c:otherwise>
                </c:choose>
                <div class="bridge-pr-help">The commit this build judged, and the one its Check Run is attached to.</div>
            </td>
        </tr>

        <tr>
            <th>Diverged at</th>
            <td>
                <c:choose>
                    <c:when test="${not empty pr.mergeBase}">
                        <code><c:out value="${pr.mergeBase}"/></code>
                        <div class="bridge-pr-help">
                            The merge base. A step that analyses only what this pull
                            request changes should diff
                            <code><c:out value="${pr.diffRange}"/></code>, available as
                            <code>%teamcity.github.bridge.pullRequest.mergeBase%</code>.
                        </div>
                    </c:when>
                    <c:otherwise>
                        <span class="bridge-pr-help">
                            Not resolved &mdash; <code>mergeBase.enabled</code> is off, or the
                            lookup failed. <strong>Do not substitute the base branch's
                            head</strong><c:if test="${not empty pr.baseSha}">
                            (<code><c:out value="${pr.baseSha}"/></code>)</c:if>: a diff against
                            it also contains everything that landed on
                            <c:out value="${pr.targetBranch}"/> since this branch started.
                        </span>
                    </c:otherwise>
                </c:choose>
            </td>
        </tr>

        <c:if test="${not empty pr.changedFiles or not empty pr.commits}">
            <tr>
                <th>Size</th>
                <td>
                    <c:if test="${not empty pr.changedFiles}">${pr.changedFiles} file(s)</c:if>
                    <c:if test="${not empty pr.additions or not empty pr.deletions}">
                        &nbsp;<span style="color:#2e7d32;">+${empty pr.additions ? 0 : pr.additions}</span>
                        <span style="color:#c62828;">&minus;${empty pr.deletions ? 0 : pr.deletions}</span>
                    </c:if>
                    <c:if test="${not empty pr.commits}">&nbsp;in ${pr.commits} commit(s)</c:if>
                    <div class="bridge-pr-help">As the pull request stood when this build ran.</div>
                </td>
            </tr>
        </c:if>

        <c:if test="${not empty pr.labels}">
            <tr>
                <th>Labels</th>
                <td>
                    <c:forEach var="label" items="${pr.labels}">
                        <span class="bridge-pr-label"><c:out value="${label}"/></span>
                    </c:forEach>
                    <div class="bridge-pr-help">The same list the metadata gate filters on.</div>
                </td>
            </tr>
        </c:if>
    </table>

    <%-- ---------- what it changes ---------- --%>

    <c:if test="${not empty changedFiles}">
        <h3>Changed files</h3>

        <div class="bridge-pr-help" style="margin-bottom:6px;">
            The pull request <strong>as it stands now</strong> &mdash; the one thing on this
            page that is not read from this build's parameters, because a list of
            paths does not belong in one. For what <em>this build</em> judged, the head
            commit above is the answer.
            <c:if test="${changedFilesTruncated}">
                GitHub capped the list, so these are the first of them, not all.
            </c:if>
        </div>

        <ul class="bridge-pr-files">
            <c:forEach var="file" items="${changedFiles}">
                <li>
                    <c:choose>
                        <c:when test="${not empty pr.filesUrl}">
                            <a href="<c:out value='${pr.filesUrl}'/>" target="_blank" rel="noopener noreferrer"><c:out value="${file}"/></a>
                        </c:when>
                        <c:otherwise><c:out value="${file}"/></c:otherwise>
                    </c:choose>
                </li>
            </c:forEach>
        </ul>

        <c:if test="${changedFilesMore gt 0}">
            <div class="bridge-pr-help">
                &hellip;and ${changedFilesMore} more.
                <c:if test="${not empty pr.filesUrl}">
                    <a href="<c:out value='${pr.filesUrl}'/>" target="_blank" rel="noopener noreferrer">See them all on GitHub <c:out value="${extMark}" escapeXml="false"/></a>
                </c:if>
            </div>
        </c:if>
    </c:if>

    <%-- ---------- what the bridge did with it ---------- --%>

    <h3>What the bridge did</h3>

    <table>
        <c:if test="${not empty checkRunName}">
            <tr>
                <th>Reports as</th>
                <td>
                    <code><c:out value="${checkRunName}"/></code>
                    <c:if test="${not empty publishes and not publishes}">
                        &nbsp;&mdash; <strong>not published</strong>
                    </c:if>
                    <div class="bridge-pr-help">
                        <c:choose>
                            <c:when test="${not empty publishes and not publishes}">
                                <em>Publish to GitHub</em> is unchecked on this build configuration, so
                                nothing about this build reaches the pull request. It still gets the PR
                                parameters and tags. This is the name it <em>would</em> use.
                            </c:when>
                            <c:otherwise>
                                The Check Run name on the commit. This is the exact string a branch
                                protection rule must require &mdash; a required check whose name never
                                arrives blocks every pull request for ever.
                            </c:otherwise>
                        </c:choose>
                    </div>
                </td>
            </tr>
        </c:if>

        <c:if test="${not empty triggerSource}">
            <tr>
                <th>Started by</th>
                <td>
                    a GitHub-side command (<code><c:out value="${triggerSource}"/></code>)
                    <div class="bridge-pr-help">
                        A PR comment, a review approval, a Re-run button or the external API.
                        An explicit request like this bypasses the soft filters &mdash; branch
                        lists, paths, PR metadata &mdash; that only narrow automatic triggers.
                    </div>
                </td>
            </tr>
        </c:if>

        <c:if test="${not empty prBuildRef}">
            <tr>
                <th>Built on</th>
                <td>
                    <c:choose>
                        <c:when test="${prBuildRef eq 'branch'}">the pull request's own head branch</c:when>
                        <c:otherwise>the <code>pull/N</code> ref</c:otherwise>
                    </c:choose>
                    <div class="bridge-pr-help">Per-project setting <code>prBuildRef</code>.</div>
                </td>
            </tr>
        </c:if>

        <c:if test="${not empty siblingsUrl}">
            <tr>
                <th>Other builds</th>
                <td>
                    <a href="${pageContext.request.contextPath}<c:out value='${siblingsUrl}'/>">
                        every build of #${pr.number} in this project
                    </a>
                    <div class="bridge-pr-help">The project's <em>Branches &amp; PRs</em> tab, filtered on this pull request.</div>
                </td>
            </tr>
        </c:if>
    </table>

    <div class="bridge-pr-help" style="margin-top:16px;">
        Everything above is what <strong>this build</strong> resolved when it ran, read
        from its own parameters: opening this page makes no GitHub call, and a pull
        request retitled or merged since does not rewrite the history of the build.
        The same values are available to build steps as
        <code>teamcity.github.bridge.pullRequest.*</code> &mdash; see the
        <em>Parameters</em> tab.
    </div>
</div>
