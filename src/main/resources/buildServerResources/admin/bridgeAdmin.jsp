<%--
  TeamCity GitHub Bridge - admin console.
  Rendered as a tab under Administration -> Server Administration; the model is
  filled by AdminConsolePage.fillModel.

  Seven in-page tabs: Overview, GitHub App, Webhook, Server settings, External
  API, Activity, Help. Every action posts to a controller that redirects back
  with `bridgeResult=<code>`; AdminConsolePage maps the code to the tab the
  action belongs to (`bridgeOpenTab`), so its result banner shows in context.
--%>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="fn" uri="http://java.sun.com/jsp/jstl/functions" %>
<%@ include file="../common/bridgeUi.jspf" %>

<style>
    .bridge-status { padding: 2px 8px; border-radius: 3px; font-weight: 600; font-size: 12px; display: inline-block; }
    .bridge-ok { background: #e8f5e9; color: #1b5e20; }
    .bridge-warn { background: #fff8e1; color: #8a6d00; }
    .bridge-bad { background: #ffebee; color: #b71c1c; }
    .bridge-kv { border-collapse: collapse; }
    .bridge-kv th { text-align: left; padding: 6px 16px 6px 0; vertical-align: top; color: #555; font-weight: normal; width: 200px; white-space: nowrap; }
    .bridge-kv td { padding: 6px 0; vertical-align: top; }
    .bridge-copy { font-family: monospace; background: #fff; border: 1px solid #d5d9e0; border-radius: 3px; padding: 2px 6px; user-select: all; }
    .bridge-grid { width: 100%; border-collapse: collapse; font-size: 12px; }
    .bridge-grid th, .bridge-grid td { border-bottom: 1px solid #eee; padding: 6px 8px; text-align: left; vertical-align: top; }
    .bridge-grid th { background: #f3f5f8; font-weight: 600; }
    .bridge-grid td.bridge-mono { white-space: nowrap; font-family: monospace; }
    .bridge-accepted, .bridge-test-pass { color: #1b5e20; font-weight: 700; }
    .bridge-skipped, .bridge-test-warn { color: #8a6d00; font-weight: 700; }
    .bridge-rejected, .bridge-test-fail { color: #b71c1c; font-weight: 700; }
    .bridge-test-skip { color: #888; font-weight: 600; }
    .bridge-steps { margin: 4px 0 0 18px; padding: 0; line-height: 1.8; }
    .bridge-inline-form { display: inline; margin: 0; }
    .bridge-flag { display: block; margin: 0 0 8px; }
    .bridge-flag .bridge-note { margin-left: 22px; }
    .bridge-sub { margin-left: 22px; }
    .bridge-danger { color: #b71c1c !important; }
    .bridge-help-list { margin: 4px 0 0 18px; padding: 0; line-height: 1.7; }
</style>

<c:set var="settingsDoc" value="${bridgeDoc}configuration.md#2-server-wide-settings-flags-and-secrets"/>

<c:if test="${not empty resultBanner}">
    <div class="bridge-banner ${resultBanner.level}"><c:out value="${resultBanner.text}"/></div>
</c:if>

<div class="bridge-tabs">
    <a href="#bridge-tab-overview" data-tab="overview">Overview</a>
    <a href="#bridge-tab-app" data-tab="app">GitHub App
        <c:if test="${not managedAppConfigured}"><span class="bridge-badge">!</span></c:if></a>
    <a href="#bridge-tab-webhook" data-tab="webhook">Webhook
        <c:if test="${not secretConfigured}"><span class="bridge-badge">!</span></c:if></a>
    <a href="#bridge-tab-settings" data-tab="settings">Server settings</a>
    <a href="#bridge-tab-api" data-tab="api">External API</a>
    <a href="#bridge-tab-activity" data-tab="activity">Activity</a>
    <a href="#bridge-tab-help" data-tab="help">Help</a>
</div>

<%-- ================= Overview ================= --%>
<div class="bridge-panel" id="bridge-tab-overview">
    <div class="bridge-section-title">Getting started</div>
    <ol class="bridge-steps">
        <li><strong>Create and install the GitHub App</strong> &mdash; <a href="#bridge-tab-app" onclick="BridgeTabs.show('bridge-tab-app');return false;">GitHub App</a> tab, one click.</li>
        <li><strong>Set the webhook secret</strong> &mdash; <a href="#bridge-tab-webhook" onclick="BridgeTabs.show('bridge-tab-webhook');return false;">Webhook</a> tab.</li>
        <li><strong>Point a project at the repository</strong> &mdash; <em>Administration &rarr; &lt;project&gt; &rarr; GitHub Bridge</em>,
            connection <code>${managedConnectionId}</code>.</li>
        <li><strong>Opt build configurations in</strong> &mdash; add the <em>GitHub Bridge integration</em> build feature.</li>
        <li><strong>Check</strong> &mdash; run the self-tests below, then open a pull request.</li>
    </ol>

    <div class="bridge-section-title">Status</div>
    <table class="bridge-kv">
        <tr><th>Plugin</th><td><code>${pluginVersion}</code> on TeamCity <code>${tcVersion}</code></td></tr>
        <tr><th>GitHub App</th><td>
            <c:choose>
                <c:when test="${managedAppConfigured}"><span class="bridge-status bridge-ok">managed</span> <code>${managedAppSlug}</code></c:when>
                <c:otherwise><span class="bridge-status bridge-warn">no managed App</span> <span class="bridge-note" style="display:inline;">fine if your projects use a TeamCity connection</span></c:otherwise>
            </c:choose>
        </td></tr>
        <tr><th>Webhook secret</th><td>
            <c:choose>
                <c:when test="${secretConfigured}"><span class="bridge-status bridge-ok">configured</span></c:when>
                <c:otherwise><span class="bridge-status bridge-bad">not set</span> every delivery is rejected with 401</c:otherwise>
            </c:choose>
        </td></tr>
        <tr><th>Dedicated log</th><td>
            <span class="bridge-status ${logConfigured ? 'bridge-ok' : 'bridge-warn'}">${logStateLabel}</span>
            <c:if test="${logConfigured}"><code>${logFile}</code></c:if>
        </td></tr>
        <tr><th>Configuration snapshot</th><td><a href="${infoUrl}" target="_blank">JSON</a> &middot; <a href="${infoMarkdownUrl}" target="_blank">Markdown</a></td></tr>
    </table>

    <div class="bridge-section-title">
        Self-tests <a class="bridge-doc" href="${bridgeDoc}api-reference.md" target="_blank" title="What each test checks">?</a>
    </div>
    <form method="post" action="${runTestsUrl}">
        <input type="hidden" name="${csrfTokenName}" value="${csrfToken}"/>
        <input type="submit" class="btn btn_primary" value="Run self-tests"/>
        <span class="bridge-note" style="display:inline;">
            Configuration, GitHub reachability, webhook round trip, tokens, and the consistency of your build configurations.
        </span>
    </form>
    <c:if test="${not empty testResults}">
        <table class="bridge-grid" style="margin-top:10px;">
            <thead><tr><th>Test</th><th>Status</th><th>Detail</th></tr></thead>
            <tbody>
            <c:forEach var="t" items="${testResults}">
                <tr>
                    <td class="bridge-mono"><c:out value="${t.name}"/></td>
                    <td><span class="${t.cssClass}">${t.status}</span></td>
                    <td><c:out value="${t.detail}"/></td>
                </tr>
            </c:forEach>
            </tbody>
        </table>
    </c:if>
</div>

<%-- ================= GitHub App ================= --%>
<div class="bridge-panel" id="bridge-tab-app">
    <c:choose>
        <c:when test="${managedAppConfigured}">
            <div class="bridge-section-title">
                Managed App <a class="bridge-doc" href="${bridgeDoc}github-app-setup.md" target="_blank" title="Documentation">?</a>
            </div>
            <p>
                <span class="bridge-status bridge-ok">configured</span> <code>${managedAppSlug}</code>
                &nbsp;&middot;&nbsp; <a href="${appSettingsUrl}" target="_blank">App settings on GitHub</a>
                &nbsp;&middot;&nbsp; <a href="${appInstallUrl}" target="_blank">Installations</a>
            </p>
            <p class="bridge-intro">Projects use it with the connection ID <code>${managedConnectionId}</code>.</p>

            <div class="bridge-section-title">Check</div>
            <form method="post" action="${saveSecretUrl}" class="bridge-inline-form">
                <input type="hidden" name="action" value="verifyApp"/>
                <input type="hidden" name="${csrfTokenName}" value="${csrfToken}"/>
                <input type="submit" class="btn btn_primary" value="Verify App configuration"/>
            </form>
            <form method="post" action="${saveSecretUrl}" class="bridge-inline-form">
                <input type="hidden" name="action" value="clearTokens"/>
                <input type="hidden" name="${csrfTokenName}" value="${csrfToken}"/>
                <input type="submit" class="btn" value="Clear cached tokens"/>
            </form>
            <div class="bridge-note">
                <em>Verify</em> compares the App's live permissions and events with what the plugin needs.
                After granting a permission (and accepting it on the installation), <em>Clear cached tokens</em>
                so the next call uses it instead of a token minted before.
            </div>
            <c:if test="${not empty appVerification}">
                <div class="bridge-banner ${appVerification['ok'] ? 'ok' : (appVerification['reachable'] ? 'warn' : 'bad')}" style="margin-top:10px;">
                    <strong>
                        <c:choose>
                            <c:when test="${appVerification['ok']}">Configuration OK.</c:when>
                            <c:when test="${not appVerification['reachable']}">GitHub unreachable.</c:when>
                            <c:otherwise>Needs attention.</c:otherwise>
                        </c:choose>
                    </strong>
                    <c:out value="${appVerification['detail']}"/>
                    <c:if test="${not empty appVerification['missingPermissions']}">
                        <div>Missing permissions: <code><c:out value="${appVerification['missingPermissions']}"/></code></div>
                    </c:if>
                    <c:if test="${not empty appVerification['missingEvents']}">
                        <div>Missing events: <code><c:out value="${appVerification['missingEvents']}"/></code></div>
                    </c:if>
                </div>
            </c:if>
            <div class="bridge-note" style="margin-top:10px;">
                Optional permissions, only for the features that need them: <strong>Issues: write</strong> (assign and label
                pull requests), <strong>Administration: read</strong> (the <em>Required checks</em> self-test on classic
                branch protection), organisation <strong>Members: read</strong> (team conditions in label rules).
            </div>
        </c:when>
        <c:otherwise>
            <div class="bridge-section-title">
                Create the GitHub App <a class="bridge-doc" href="${bridgeDoc}github-app-setup.md" target="_blank" title="Documentation">?</a>
            </div>
            <p class="bridge-intro">
                GitHub creates an App with the right webhook URL, permissions and events, and hands its credentials
                back to this page. Install it on your organisation or repositories afterwards.
                Already have an App? Use its TeamCity connection instead &mdash; see the documentation.
            </p>
            <form id="bridge-create-app" method="post" action="https://github.com/settings/apps/new?state=${appState}">
                <input type="hidden" name="manifest" value="<c:out value='${appManifestJson}'/>"/>
                <table class="bridge-form">
                    <tr>
                        <th><label for="bridge-app-org">Organisation:</label></th>
                        <td>
                            <input type="text" id="bridge-app-org" placeholder="my-org" style="width:200px;"/>
                            <div class="bridge-note">Leave empty for an App owned by your personal account.</div>
                        </td>
                    </tr>
                </table>
                <div class="bridge-actions"><input type="submit" class="btn btn_primary" value="Create GitHub App"/></div>
            </form>
            <script type="text/javascript">
                (function () {
                    var f = document.getElementById('bridge-create-app');
                    f.addEventListener('submit', function () {
                        var org = (document.getElementById('bridge-app-org').value || '').trim();
                        f.action = org
                            ? 'https://github.com/organizations/' + encodeURIComponent(org) + '/settings/apps/new?state=${appState}'
                            : 'https://github.com/settings/apps/new?state=${appState}';
                    });
                })();
            </script>
        </c:otherwise>
    </c:choose>
</div>

<%-- ================= Webhook ================= --%>
<div class="bridge-panel" id="bridge-tab-webhook">
    <div class="bridge-section-title">
        Secret <a class="bridge-doc" href="${bridgeDoc}webhook-setup.md" target="_blank" title="Documentation">?</a>
    </div>
    <p>
        <c:choose>
            <c:when test="${secretConfigured}">
                <span class="bridge-status bridge-ok">configured</span>
                <c:if test="${secretSource == 'INTERNAL_PROPERTIES'}">
                    <span class="bridge-note" style="display:inline;">from <code>internal.properties</code> (legacy); saving one here takes over</span>
                </c:if>
            </c:when>
            <c:otherwise><span class="bridge-status bridge-bad">not set</span> every webhook delivery is rejected with 401.</c:otherwise>
        </c:choose>
    </p>
    <form method="post" action="${saveSecretUrl}" autocomplete="off">
        <input type="hidden" name="action" value="set"/>
        <input type="hidden" name="${csrfTokenName}" value="${csrfToken}"/>
        <table class="bridge-form">
            <tr>
                <th><label for="bridge-secret-input">${secretConfigured ? 'New secret:' : 'Secret:'}</label></th>
                <td>
                    <input id="bridge-secret-input" type="password" name="secret" placeholder="a long random string" autocomplete="new-password" required/>
                    <input type="submit" class="btn btn_primary" value="Save"/>
                    <div class="bridge-note">The same value as the App's webhook secret. Generate one with <code>openssl rand -hex 48</code>; it is never shown again.</div>
                </td>
            </tr>
        </table>
    </form>
    <c:if test="${secretConfigured}">
        <form method="post" action="${saveSecretUrl}"
              onsubmit="return confirm('Clear the webhook secret? Every webhook delivery will be rejected with 401 until a new one is set.');">
            <input type="hidden" name="action" value="clear"/>
            <input type="hidden" name="${csrfTokenName}" value="${csrfToken}"/>
            <input type="submit" class="btn bridge-danger" value="Clear secret"/>
        </form>
    </c:if>

    <div class="bridge-section-title">On the GitHub App</div>
    <p class="bridge-intro">The managed App is created with these. For an App wired by hand, copy them to its <em>Webhook</em> settings.</p>
    <table class="bridge-kv">
        <tr><th>Payload URL</th><td><span class="bridge-copy">${webhookUrl}</span></td></tr>
        <tr><th>Content type</th><td><code>application/json</code></td></tr>
        <tr><th>Secret</th><td>the value saved above</td></tr>
        <tr><th>Events</th><td><c:forEach var="ev" items="${recommendedEvents}" varStatus="s"><code>${ev}</code><c:if test="${not s.last}">, </c:if></c:forEach></td></tr>
    </table>
</div>

<%-- ================= Server settings ================= --%>
<div class="bridge-panel" id="bridge-tab-settings">
    <p class="bridge-intro">
        Server-wide, applied at once without a restart. An empty text field falls back to its default.
        <a class="bridge-doc" href="${settingsDoc}" target="_blank" title="Documentation">?</a>
    </p>
    <form method="post" action="${saveSettingsUrl}">
        <input type="hidden" name="action" value="saveSettings"/>
        <input type="hidden" name="${csrfTokenName}" value="${csrfToken}"/>

        <div class="bridge-section-title">Pull-request builds</div>
        <label class="bridge-flag"><input type="checkbox" name="queueCleanup" <c:if test="${set_queueCleanup}">checked</c:if>/> Remove the builds the bridge suppresses
            <div class="bridge-note">Drafts, out-of-scope filters, already-passed commits, closed PRs. Off: the bridge only adds and reports.</div></label>
        <label class="bridge-flag"><input type="checkbox" name="cancelObsolete" <c:if test="${set_cancelObsolete}">checked</c:if>/> Stop running builds whose result has nowhere to go
            <div class="bridge-note">After a new push or when the PR closes. Never a personal or hand-started build. Needs the option above.</div></label>
        <label class="bridge-flag"><input type="checkbox" name="rerunAllOnlyFailed" <c:if test="${set_rerunAllOnlyFailed}">checked</c:if>/> "Re-run all checks" re-runs only the failed ones</label>
        <label class="bridge-flag"><input type="checkbox" name="branchPrLookup" <c:if test="${set_branchPrLookup}">checked</c:if>/> Attach branch builds to their pull request
            <div class="bridge-note">Looks the PR up from the built commit, for builds started on a plain branch.</div></label>
        <label class="bridge-flag"><input type="checkbox" name="mergeBase" <c:if test="${set_mergeBase}">checked</c:if>/> Resolve the pull request's merge base
            <div class="bridge-note">Fills <code>&hellip;pullRequest.mergeBase</code>, the right base for a "what did this PR change" diff.</div></label>
        <label class="bridge-flag"><input type="checkbox" name="prTag" <c:if test="${set_prTag}">checked</c:if>/> Tag PR builds with their number
            <div class="bridge-note">Makes them findable with TeamCity's tag search.</div></label>
        <div class="bridge-sub">
            <label class="bridge-flag"><input type="checkbox" name="prTagDisplay" <c:if test="${set_prTagDisplay}">checked</c:if>/> Show the tag in build lists
                <div class="bridge-note">Untick to keep it searchable without crowding the <code>draft</code>/<code>ready</code> pill.</div></label>
            <label class="bridge-flag">Tag prefix: <input type="text" name="prTagPrefix" size="10" value="<c:out value="${set_prTagPrefix}"/>"/>
                <span class="bridge-note" style="display:inline;">default <code>pr-</code></span></label>
        </div>

        <div class="bridge-section-title">What a Check Run reports</div>
        <label class="bridge-flag"><input type="checkbox" name="testStats" <c:if test="${set_testStats}">checked</c:if>/> Test outcome
            <div class="bridge-note">Counts in the title, failing tests in the body, new failures first.</div></label>
        <label class="bridge-flag"><input type="checkbox" name="timings" <c:if test="${set_timings}">checked</c:if>/> Timings
            <div class="bridge-note">Total, working time and what the build waited for.</div></label>
        <label class="bridge-flag"><input type="checkbox" name="artifactLinks" <c:if test="${set_artifactLinks}">checked</c:if>/> Artifact links</label>
        <label class="bridge-flag"><input type="checkbox" name="annotations" <c:if test="${set_annotations}">checked</c:if>/> Annotate the diff with compiler diagnostics
            <div class="bridge-note">Projects and build configurations can also turn it off; any "off" wins.</div></label>
        <div class="bridge-sub">
            <label class="bridge-flag"><input type="checkbox" name="annotationLogScan" <c:if test="${set_annotationLogScan}">checked</c:if>/> Read the build log when build problems carry none
                <div class="bridge-note">Needed for Command Line runners (most CMake/ninja builds). Failed builds only, bounded.</div></label>
        </div>
        <label class="bridge-flag"><input type="checkbox" name="infraNeutral" <c:if test="${set_infraNeutral}">checked</c:if>/> An infrastructure failure does not block the merge
            <div class="bridge-note">Concludes <code>neutral</code> instead of <code>failure</code>. Off by default: turn it on once you trust the classification.</div></label>

        <div class="bridge-section-title">Access</div>
        <table class="bridge-form">
            <tr>
                <th><label for="set-allowlist">Repository allowlist:</label></th>
                <td>
                    <textarea id="set-allowlist" name="repoAllowlist" rows="3" style="width:380px;"><c:out value="${set_repoAllowlist}"/></textarea>
                    <div class="bridge-note">One <code>owner/name</code> per line. Empty: every repository.</div>
                </td>
            </tr>
            <tr>
                <th><label for="set-assoc">Comment-trigger authors:</label></th>
                <td>
                    <input type="text" id="set-assoc" name="commentAssociations" value="<c:out value='${set_commentAssociations}'/>"/>
                    <div class="bridge-note">GitHub <code>author_association</code> values, default <code>OWNER,MEMBER,COLLABORATOR</code>.</div>
                </td>
            </tr>
        </table>
        <label class="bridge-flag"><input type="checkbox" name="replayEnabled" <c:if test="${set_replayEnabled}">checked</c:if>/> Reject replayed webhook deliveries</label>

        <div class="bridge-section-title">GitHub API</div>
        <table class="bridge-form">
            <tr>
                <th><label for="set-apiBase">API base:</label></th>
                <td>
                    <input type="text" id="set-apiBase" name="apiBase" value="<c:out value='${set_apiBase}'/>" placeholder="derived from each connection"/>
                    <div class="bridge-note">Empty: <code>api.github.com</code>, or <code>&lt;host&gt;/api/v3</code> for GitHub Enterprise.</div>
                </td>
            </tr>
            <tr>
                <th><label for="set-apiVersion">API version:</label></th>
                <td><input type="text" id="set-apiVersion" name="apiVersion" value="<c:out value='${set_apiVersion}'/>" placeholder="2022-11-28" style="width:160px;"/></td>
            </tr>
            <tr>
                <th><label for="set-attempts">Retries:</label></th>
                <td>
                    <input type="number" min="1" max="10" id="set-attempts" name="httpMaxAttempts" value="${set_httpMaxAttempts}" style="width:70px;"/> attempts,
                    <input type="number" min="0" name="httpBaseDelayMs" value="${set_httpBaseDelayMs}" style="width:90px;"/> ms base delay
                </td>
            </tr>
            <tr>
                <th><label for="set-ttl">PR-info cache:</label></th>
                <td>
                    <input type="number" min="0" id="set-ttl" name="ttlSeconds" value="${set_ttlSeconds}" style="width:90px;"/> s,
                    served stale for <input type="number" min="0" name="staleGraceSeconds" value="${set_staleGraceSeconds}" style="width:90px;"/> s when GitHub fails
                </td>
            </tr>
        </table>

        <div class="bridge-section-title">Operations</div>
        <label class="bridge-flag"><input type="checkbox" name="dryRun" <c:if test="${set_dryRun}">checked</c:if>/> Dry-run
            <div class="bridge-note">Log what the bridge would do, do none of it.</div></label>
        <label class="bridge-flag"><input type="checkbox" name="metricsEnabled" <c:if test="${set_metricsEnabled}">checked</c:if>/> Metrics endpoint</label>
        <label class="bridge-flag"><input type="checkbox" name="prTabChangedFiles" <c:if test="${set_prTabChangedFiles}">checked</c:if>/> List changed files on the build's <em>Pull request</em> tab
            <div class="bridge-note">Off: opening a build page never calls GitHub.</div></label>
        <label class="bridge-flag"><input type="checkbox" name="legacyAliases" <c:if test="${set_legacyAliases}">checked</c:if>/> Publish the legacy <code>teamcity.pullRequest.*</code> parameters
            <div class="bridge-note">For build scripts written for TeamCity's bundled Pull Requests feature.</div></label>

        <div class="bridge-actions"><input type="submit" class="btn btn_primary" value="Save server settings"/></div>
    </form>
</div>

<%-- ================= External API ================= --%>
<div class="bridge-panel" id="bridge-tab-api">
    <div class="bridge-section-title">
        API token <a class="bridge-doc" href="${bridgeDoc}api-reference.md" target="_blank" title="Documentation">?</a>
    </div>
    <p class="bridge-intro">
        Enables the authenticated API under <code>/app/teamcity-github-bridge/api/</code> (status, events, metrics,
        build trigger), called with <code>Authorization: Bearer &lt;token&gt;</code>.
    </p>
    <p>
        <c:choose>
            <c:when test="${apiTokenConfigured}"><span class="bridge-status bridge-ok">enabled</span></c:when>
            <c:otherwise><span class="bridge-status bridge-warn">disabled</span> until a token is set</c:otherwise>
        </c:choose>
    </p>
    <form method="post" action="${saveSecretUrl}" autocomplete="off">
        <input type="hidden" name="action" value="setApiToken"/>
        <input type="hidden" name="${csrfTokenName}" value="${csrfToken}"/>
        <table class="bridge-form">
            <tr>
                <th><label for="bridge-apitoken-input">${apiTokenConfigured ? 'New token:' : 'Token:'}</label></th>
                <td>
                    <input id="bridge-apitoken-input" type="password" name="apiToken" placeholder="a long random string" autocomplete="new-password" required/>
                    <input type="submit" class="btn btn_primary" value="Save"/>
                    <div class="bridge-note">Generate one with <code>openssl rand -hex 32</code>; it is never shown again.</div>
                </td>
            </tr>
        </table>
    </form>
    <c:if test="${apiTokenConfigured}">
        <form method="post" action="${saveSecretUrl}"
              onsubmit="return confirm('Clear the API token? The external API will be disabled until a new one is set.');">
            <input type="hidden" name="action" value="clearApiToken"/>
            <input type="hidden" name="${csrfTokenName}" value="${csrfToken}"/>
            <input type="submit" class="btn bridge-danger" value="Disable the API"/>
        </form>
    </c:if>
</div>

<%-- ================= Activity ================= --%>
<div class="bridge-panel" id="bridge-tab-activity">
    <p class="bridge-intro">
        The last ${fn:length(recentEvents)} webhook deliveries, kept in memory; the dedicated log has the full history.
        <a class="bridge-doc" href="${bridgeDoc}troubleshooting.md" target="_blank" title="Troubleshooting">?</a>
    </p>
    <c:choose>
        <c:when test="${empty recentEvents}">
            <p class="bridge-note">No delivery yet. GitHub sends a ping when the webhook is set; redeliver it from the App's <em>Advanced</em> page.</p>
        </c:when>
        <c:otherwise>
            <table class="bridge-grid">
                <thead><tr><th>Time</th><th>Event</th><th>Action</th><th>Repository</th><th>HTTP</th><th>Outcome</th><th>Detail</th></tr></thead>
                <tbody>
                <c:forEach var="e" items="${recentEvents}">
                    <tr>
                        <td class="bridge-mono">${e.timestamp}</td>
                        <td class="bridge-mono"><c:out value="${e.event}"/></td>
                        <td class="bridge-mono"><c:out value="${e.action}"/></td>
                        <td class="bridge-mono"><c:out value="${e.repo}"/></td>
                        <td>${e.httpStatus}</td>
                        <td class="${e.outcomeClass}"><c:out value="${e.outcome}"/></td>
                        <td><c:out value="${e.detail}"/></td>
                    </tr>
                </c:forEach>
                </tbody>
            </table>
        </c:otherwise>
    </c:choose>
</div>

<%-- ================= Help ================= --%>
<div class="bridge-panel" id="bridge-tab-help">
    <div class="bridge-section-title">Documentation</div>
    <ul class="bridge-help-list">
        <li><a href="${bridgeDoc}quickstart.md" target="_blank">Quickstart</a> &mdash; from zero to a Check Run</li>
        <li><a href="${bridgeDoc}configuration.md" target="_blank">Configuration reference</a> &mdash; every setting, on every screen</li>
        <li><a href="${bridgeDoc}github-app-setup.md" target="_blank">GitHub App</a> and <a href="${bridgeDoc}webhook-setup.md" target="_blank">webhook</a> setup</li>
        <li><a href="${bridgeDoc}usage-scenarios.md" target="_blank">Usage scenarios</a> &middot; <a href="${bridgeDoc}branching-workflows.md" target="_blank">Branching workflows</a></li>
        <li><a href="${bridgeDoc}troubleshooting.md" target="_blank">Troubleshooting</a> &middot; <a href="${bridgeDoc}upgrading.md" target="_blank">Upgrading</a> &middot; <a href="${bridgeDoc}security.md" target="_blank">Security model</a></li>
        <li><a href="${bridgeDoc}api-reference.md" target="_blank">HTTP API and self-tests</a> &middot; <a href="${bridgeDoc}architecture.md" target="_blank">Architecture</a></li>
    </ul>

    <div class="bridge-section-title">Quick checks</div>
    <ul class="bridge-help-list">
        <li><strong>401 Invalid signature</strong> &mdash; the secret differs between here and the App; paste both again, no surrounding spaces.</li>
        <li><strong>404 on the webhook URL</strong> &mdash; a reverse proxy strips <code>/app/&hellip;</code>, or the plugin is not loaded.</li>
        <li><strong>Webhook URL shows <code>http://</code></strong> &mdash; the proxy does not forward <code>X-Forwarded-Proto</code>.</li>
        <li><strong>Two rows per build on GitHub</strong> &mdash; the bundled <em>Commit status publisher</em> is also on; disable it on opted-in configurations.</li>
        <li><strong>A required check never arrives</strong> &mdash; run the self-tests: <em>Required checks</em> lists the names nothing posts.</li>
        <li><strong>Plugin entries in <code>teamcity-server.log</code></strong> &mdash; merge <code>/${snippetResourceName}</code> into
            <code>&lt;TC_DATA_DIR&gt;/config/teamcity-server-log4j.xml</code> for a dedicated log.</li>
    </ul>
</div>

<script type="text/javascript">BridgeTabs.init('${bridgeOpenTab}');</script>
