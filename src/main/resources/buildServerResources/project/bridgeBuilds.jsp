<%@ include file="/include-internal.jsp" %>
<%--
  Branch/PR view of the bridge's builds. One list keyed by both the branch
  and the pull request, searchable by either (see BridgeBuildsTab).
--%>
<style>
    .bridgeBuilds { border-collapse: collapse; width: 100%; margin-top: 1em; }
    .bridgeBuilds th, .bridgeBuilds td { padding: 4px 8px; text-align: left; border-bottom: 1px solid #e6e6e6; vertical-align: top; }
    .bridgeBuilds th { font-weight: bold; white-space: nowrap; }
    .bridgeBuilds .lvl-ok { color: #1a8c1a; }
    .bridgeBuilds .lvl-bad { color: #c22; }
    .bridgeBuilds .lvl-pending { color: #888; }
    .bridgePill { display: inline-block; padding: 0 6px; border-radius: 8px; font-size: 11px; line-height: 16px; }
    .bridgePill.draft { background: #ffe9b3; color: #7a5b00; }
    .bridgePill.ready { background: #d4f5d4; color: #1a6b1a; }
    .bridgeSearch { margin-bottom: .5em; }
    .bridgeHint { color: #888; margin-left: .5em; }
    .bridgeEmpty { color: #888; margin-top: 1em; }
</style>

<div class="bridgeSearch">
    <form method="get" action="">
        <input type="hidden" name="tab" value="bridgeBuilds"/>
        <input type="hidden" name="projectId" value="<c:out value="${project.externalId}"/>"/>
        <input type="text" name="q" size="30" value="<c:out value="${query}"/>"
               placeholder="branch name or PR number"/>
        <input type="submit" class="btn" value="Search"/>
        <span class="bridgeHint">
            Type a branch name (<code>Feature/</code>) or a PR number (<code>189</code>, <code>#189</code>).
        </span>
    </form>
</div>

<c:set var="baseUrl" value="?tab=bridgeBuilds&projectId=${project.externalId}&q=${query}"/>
<div>
    Sort by:
    <a href="${baseUrl}&sort=time"><c:if test="${sort == 'time'}"><b></c:if>most recent<c:if test="${sort == 'time'}"></b></c:if></a> |
    <a href="${baseUrl}&sort=branch"><c:if test="${sort == 'branch'}"><b></c:if>branch<c:if test="${sort == 'branch'}"></b></c:if></a> |
    <a href="${baseUrl}&sort=pr"><c:if test="${sort == 'pr'}"><b></c:if>pull request<c:if test="${sort == 'pr'}"></b></c:if></a>
</div>

<c:choose>
    <c:when test="${empty rows}">
        <div class="bridgeEmpty">
            No builds to show. This view lists the last ${historyDepth} builds of each build
            configuration that carries the <b>GitHub Bridge integration</b> feature, plus
            everything queued or running.
        </div>
    </c:when>
    <c:otherwise>
        <table class="bridgeBuilds">
            <tr>
                <th>Branch</th>
                <th>PR</th>
                <th>Build configuration</th>
                <th>Build</th>
                <th>State</th>
                <th>Artifacts</th>
            </tr>
            <c:forEach var="row" items="${rows}">
                <tr>
                    <td>
                        <c:out value="${row.branch}"/>
                        <c:if test="${row.draft ne null}">
                            <span class="bridgePill ${row.draft ? 'draft' : 'ready'}">${row.draft ? 'draft' : 'ready'}</span>
                        </c:if>
                    </td>
                    <td>
                        <c:if test="${row.prNumber ne null}">#${row.prNumber}</c:if>
                    </td>
                    <td><c:out value="${row.buildTypeName}"/></td>
                    <td>
                        <c:choose>
                            <c:when test="${not empty row.url}">
                                <a href="<c:out value="${row.url}"/>"><c:out value="${empty row.buildNumber ? 'queued' : row.buildNumber}"/></a>
                            </c:when>
                            <c:otherwise><c:out value="${row.buildNumber}"/></c:otherwise>
                        </c:choose>
                    </td>
                    <td class="lvl-${row.level}"><c:out value="${row.state}"/></td>
                    <td>
                        <c:if test="${not empty row.artifactsUrl}">
                            <a href="<c:out value="${row.artifactsUrl}"/>">artifacts</a>
                        </c:if>
                    </td>
                </tr>
            </c:forEach>
        </table>
    </c:otherwise>
</c:choose>
