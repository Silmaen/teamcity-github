<%--
  TeamCity GitHub Bridge - admin console JSP.
  Rendered as a tab under Administration -> Server Administration.
  Model attributes are populated by AdminConsolePage.fillModel.
--%>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="fn" uri="http://java.sun.com/jsp/jstl/functions" %>

<style>
    .bridge-section { margin: 0 0 24px 0; }
    .bridge-section h2 { margin: 0 0 8px 0; }
    .bridge-status { padding: 6px 10px; border-radius: 4px; font-weight: 600; display: inline-block; }
    .bridge-ok { background: #e8f5e9; color: #1b5e20; }
    .bridge-warn { background: #fff8e1; color: #ad8400; }
    .bridge-bad { background: #ffebee; color: #b71c1c; }
    .bridge-card { border: 1px solid #ddd; border-radius: 6px; padding: 16px; background: #fafafa; }
    .bridge-kv { width: 100%; border-collapse: collapse; }
    .bridge-kv th { text-align: left; padding: 6px 8px; width: 28%; vertical-align: top; color: #555; font-weight: 500; }
    .bridge-kv td { padding: 6px 8px; vertical-align: top; }
    .bridge-copy { font-family: monospace; background: #fff; border: 1px solid #ddd; border-radius: 3px; padding: 2px 6px; }
    .bridge-events { width: 100%; border-collapse: collapse; font-size: 13px; }
    .bridge-events th, .bridge-events td { border-bottom: 1px solid #eee; padding: 6px 8px; text-align: left; vertical-align: top; }
    .bridge-events th { background: #f0f0f0; }
    .bridge-accepted { color: #1b5e20; font-weight: 600; }
    .bridge-skipped { color: #ad8400; font-weight: 600; }
    .bridge-rejected { color: #b71c1c; font-weight: 600; }
    .bridge-help { background: #f5f5f5; border-radius: 6px; padding: 16px; margin-top: 24px; }
    .bridge-help h3 { margin-top: 0; }
    .bridge-help ul { margin: 4px 0 0 18px; padding: 0; }
    details.bridge-fold > summary { cursor: pointer; font-weight: 600; padding: 4px 0; }
    pre.bridge-snippet { background: #f7f7f7; border: 1px solid #ddd; padding: 12px; overflow-x: auto; font-size: 12px; }
    .bridge-banner { padding: 10px 14px; border-radius: 6px; margin: 0 0 16px 0; font-weight: 500; }
    .bridge-banner-ok { background: #e8f5e9; color: #1b5e20; border: 1px solid #a5d6a7; }
    .bridge-banner-warn { background: #fff8e1; color: #ad8400; border: 1px solid #ffe082; }
    .bridge-banner-bad { background: #ffebee; color: #b71c1c; border: 1px solid #ef9a9a; }
    form.bridge-runtests-form { margin: 0 0 16px 0; padding: 12px; background: #fff; border: 1px solid #ddd; border-radius: 4px; display: flex; align-items: center; gap: 12px; }
    form.bridge-runtests-form button { padding: 6px 16px; background: #1976d2; color: white; border: none; border-radius: 3px; cursor: pointer; font-weight: 600; }
    form.bridge-runtests-form button:hover { background: #1565c0; }
    .bridge-test-pass { color: #1b5e20; font-weight: 700; }
    .bridge-test-warn { color: #ad8400; font-weight: 700; }
    .bridge-test-fail { color: #b71c1c; font-weight: 700; }
    .bridge-test-skip { color: #888; font-weight: 600; }
    table.bridge-tests { width: 100%; border-collapse: collapse; font-size: 13px; }
    table.bridge-tests th, table.bridge-tests td { border-bottom: 1px solid #eee; padding: 6px 10px; text-align: left; vertical-align: top; }
    table.bridge-tests th { background: #f0f0f0; }
    table.bridge-tests td.bridge-test-name { white-space: nowrap; font-family: monospace; }
    table.bridge-tests td.bridge-test-status { white-space: nowrap; }
    form.bridge-secret-form { margin: 12px 0 0 0; padding: 12px; background: #fff; border: 1px solid #ddd; border-radius: 4px; }
    form.bridge-secret-form label { display: block; margin: 0 0 6px 0; font-size: 12px; color: #555; }
    form.bridge-secret-form input[type=password] { width: 360px; padding: 5px 8px; font-family: monospace; border: 1px solid #ccc; border-radius: 3px; }
    form.bridge-secret-form button { margin-left: 6px; padding: 5px 12px; }
    form.bridge-secret-form .bridge-hint { font-size: 11px; color: #888; margin-top: 6px; }
</style>

<h1 style="margin-top: 0;">TeamCity GitHub Bridge</h1>
<p>
    Server-side plugin that closes the gap between TeamCity and GitHub:
    draft PR awareness, automatic retrigger on ready-for-review,
    App-level webhooks with HMAC verification.
</p>

<c:if test="${not empty resultBanner}">
    <div class="bridge-banner bridge-banner-${resultBanner.level}">
        ${resultBanner.text}
    </div>
</c:if>

<div class="bridge-section bridge-card">
    <h2>Getting started</h2>
    <ol style="margin:4px 0 0 18px; padding:0; line-height:1.7;">
        <li><strong>Create &amp; install the GitHub App</strong> &mdash; use the <em>GitHub App</em> card below
            (one click; webhook URL, permissions and events are pre-filled). Then install it on your org/repos.</li>
        <li><strong>Point a project at it</strong> &mdash; open <em>Administration &rarr; &lt;your project&gt; &rarr; GitHub Bridge</em>,
            set the repository and <code>connectionId=${managedConnectionId}</code>.</li>
        <li><strong>Opt a build configuration in</strong> &mdash; add the <em>GitHub Bridge integration</em> build feature
            (Build Features tab) to each build configuration that should report to GitHub.</li>
        <li><strong>Verify</strong> &mdash; use <em>Verify App configuration</em> (GitHub App card) and <em>Run self-tests</em> (below),
            then open a pull request and watch the Check Run appear.</li>
    </ol>
    <p style="font-size:12px; color:#888; margin-bottom:0;">
        Prefer to wire an existing App by hand? See the
        <a href="https://github.com/silmaen/teamcity-github/blob/main/doc/github-app-setup.md" target="_blank">manual setup guide</a>.
    </p>
</div>

<c:if test="${not empty testResults}">
    <div class="bridge-section">
        <h2>Self-test results</h2>
        <table class="bridge-tests">
            <thead>
                <tr>
                    <th>Test</th>
                    <th>Status</th>
                    <th>Detail</th>
                </tr>
            </thead>
            <tbody>
                <c:forEach var="t" items="${testResults}">
                    <tr>
                        <td class="bridge-test-name">${t.name}</td>
                        <td class="bridge-test-status"><span class="${t.cssClass}">${t.status}</span></td>
                        <td>${t.detail}</td>
                    </tr>
                </c:forEach>
            </tbody>
        </table>
    </div>
</c:if>

<div class="bridge-section bridge-card">
    <h2>Plugin status</h2>
    <table class="bridge-kv">
        <tr>
            <th>Plugin version</th>
            <td><code>${pluginVersion}</code></td>
        </tr>
        <tr>
            <th>TeamCity version</th>
            <td><code>${tcVersion}</code></td>
        </tr>
        <tr>
            <th>Webhook URL</th>
            <td><span class="bridge-copy">${webhookUrl}</span></td>
        </tr>
        <tr>
            <th>HMAC secret</th>
            <td>
                <c:choose>
                    <c:when test="${secretConfigured}">
                        <span class="bridge-status bridge-ok">configured</span>
                        <c:choose>
                            <c:when test="${secretSource == 'PLUGIN_SETTINGS'}">
                                &nbsp;<small>(via this page)</small>
                            </c:when>
                            <c:when test="${secretSource == 'INTERNAL_PROPERTIES'}">
                                &nbsp;<small>(via <code>teamcity.github.bridge.webhook.secret</code> in <code>internal.properties</code> - legacy)</small>
                            </c:when>
                        </c:choose>
                    </c:when>
                    <c:otherwise>
                        <span class="bridge-status bridge-bad">NOT configured</span>
                        &nbsp;Webhooks will be rejected with 401 until a secret is set.
                    </c:otherwise>
                </c:choose>

                <form class="bridge-secret-form" method="post" action="${saveSecretUrl}" autocomplete="off">
                    <label for="bridge-secret-input">
                        <c:choose>
                            <c:when test="${secretConfigured}">Replace the secret:</c:when>
                            <c:otherwise>Set the secret:</c:otherwise>
                        </c:choose>
                    </label>
                    <input id="bridge-secret-input" type="password" name="secret" placeholder="paste a strong random string" autocomplete="new-password" required/>
                    <input type="hidden" name="action" value="set"/>
                    <input type="hidden" name="${csrfTokenName}" value="${csrfToken}"/>
                    <button type="submit">Save</button>
                    <div class="bridge-hint">
                        Stored in <code>&lt;TC_DATA_DIR&gt;/config/teamcity-github-bridge.properties</code>.
                        The value is never echoed back; rotate it by submitting a new one.
                        Generate with <code>openssl rand -hex 48</code>.
                    </div>
                </form>
                <c:if test="${secretConfigured}">
                    <form method="post" action="${saveSecretUrl}" style="margin-top: 6px;"
                          onsubmit="return confirm('Clear the webhook secret? Every webhook delivery will be rejected with 401 until a new one is set.');">
                        <input type="hidden" name="action" value="clear"/>
                        <input type="hidden" name="${csrfTokenName}" value="${csrfToken}"/>
                        <button type="submit" style="color: #b71c1c; background: transparent; border: 1px solid #b71c1c; padding: 4px 10px; border-radius: 3px;">Clear secret</button>
                    </form>
                </c:if>
            </td>
        </tr>
        <tr>
            <th>Dedicated log</th>
            <td>
                <c:choose>
                    <c:when test="${logConfigured}">
                        <span class="bridge-status bridge-ok">${logStateLabel}</span>
                        &nbsp;<code>${logFile}</code>
                    </c:when>
                    <c:otherwise>
                        <span class="bridge-status bridge-warn">${logStateLabel}</span>
                        &nbsp;The plugin attaches the appender at startup; restart TC if this stays warning.
                    </c:otherwise>
                </c:choose>
            </td>
        </tr>
        <tr>
            <th>Config snapshot</th>
            <td>
                <a href="${infoUrl}" target="_blank">JSON</a>
                &middot;
                <a href="${infoMarkdownUrl}" target="_blank">Markdown</a>
            </td>
        </tr>
    </table>
</div>

<div class="bridge-section bridge-card">
    <h2>GitHub App</h2>
    <c:choose>
        <c:when test="${managedAppConfigured}">
            <p>
                <span class="bridge-status bridge-ok">managed App configured</span>
                &nbsp;<code>${managedAppSlug}</code>
            </p>
            <p style="font-size:13px;">
                <a href="${appSettingsUrl}" target="_blank">Open App settings on GitHub</a>
                &middot;
                <a href="${appInstallUrl}" target="_blank">Install / manage installations</a>
            </p>
            <p style="font-size:13px; color:#555;">
                To use this App, set <code>connectionId=${managedConnectionId}</code> on your build
                configurations (project &rarr; GitHub Bridge tab), instead of a TeamCity connection ID.
            </p>
            <form method="post" action="${saveSecretUrl}" style="margin:8px 0;">
                <input type="hidden" name="action" value="verifyApp"/>
                <input type="hidden" name="${csrfTokenName}" value="${csrfToken}"/>
                <button type="submit" style="padding:6px 16px; background:#1976d2; color:#fff; border:none; border-radius:3px; font-weight:600; cursor:pointer;">Verify App configuration</button>
                <span style="color:#555; font-size:13px;">Calls <code>GET /app</code> and checks the App's live permissions &amp; subscribed events against what the plugin needs.</span>
            </form>
            <form method="post" action="${saveSecretUrl}" style="margin:8px 0;">
                <input type="hidden" name="action" value="clearTokens"/>
                <input type="hidden" name="${csrfTokenName}" value="${csrfToken}"/>
                <button type="submit" style="padding:6px 16px; cursor:pointer;">Clear cached tokens</button>
                <span style="color:#555; font-size:13px;">An installation token carries the permissions it had <strong>when it was minted</strong>, and the plugin caches it for 50&nbsp;minutes. So after granting the App a permission &mdash; and <strong>accepting</strong> the request on the installation, which GitHub asks separately &mdash; calls keep failing with <code>403</code> until the cached token expires. Press this instead of waiting. Drops the PR-info cache too, since a token with new scopes may see more than the old one did.</span>
            </form>
            <c:if test="${not empty appVerification}">
                <div style="margin-top:8px;">
                    <c:choose>
                        <c:when test="${appVerification['ok']}">
                            <span class="bridge-status bridge-ok">configuration OK</span>
                        </c:when>
                        <c:when test="${not appVerification['reachable']}">
                            <span class="bridge-status bridge-bad">unreachable</span> &nbsp;${appVerification['detail']}
                        </c:when>
                        <c:otherwise>
                            <span class="bridge-status bridge-warn">needs attention</span>
                        </c:otherwise>
                    </c:choose>
                    <div style="font-size:13px; margin-top:4px;"><c:out value="${appVerification['detail']}"/></div>
                    <c:if test="${not empty appVerification['missingPermissions']}">
                        <div style="font-size:13px; margin-top:4px;">Missing permissions: <code><c:out value="${appVerification['missingPermissions']}"/></code></div>
                    </c:if>
                    <c:if test="${not empty appVerification['missingEvents']}">
                        <div style="font-size:13px;">Missing events: <code><c:out value="${appVerification['missingEvents']}"/></code></div>
                    </c:if>
                </div>
            </c:if>
        </c:when>
        <c:otherwise>
            <p style="color:#555; font-size:13px; margin-top:0;">
                Let the plugin create a pre-configured GitHub App for you (correct
                webhook URL, permissions, and events). GitHub shows a confirmation
                screen; after you create it, the credentials are stored here
                automatically. You still install the App on your org/repos afterwards.
            </p>
            <form id="bridge-create-app" method="post" action="https://github.com/settings/apps/new?state=${appState}">
                <input type="hidden" name="manifest" id="bridge-manifest" value="<c:out value='${appManifestJson}'/>"/>
                <label style="font-size:13px;">GitHub organisation (optional, leave blank for a personal App):
                    <input type="text" id="bridge-app-org" placeholder="my-org" style="width:200px;"/>
                </label>
                <br/>
                <button type="submit" style="margin-top:8px; padding:6px 16px; background:#2e7d32; color:#fff; border:none; border-radius:3px; font-weight:600; cursor:pointer;">Create GitHub App</button>
            </form>
            <script>
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

<div class="bridge-section bridge-card">
    <h2>Server settings</h2>
    <p style="color:#555; font-size:13px; margin-top:0;">
        Tuning and feature flags applied server-wide. Saved to
        <code>&lt;TC_DATA_DIR&gt;/config/teamcity-github-bridge.properties</code>
        and applied immediately (no restart). Leave a text field blank to
        revert it to the default / legacy internal property.
    </p>
    <form method="post" action="${saveSettingsUrl}">
        <input type="hidden" name="action" value="saveSettings"/>
        <input type="hidden" name="${csrfTokenName}" value="${csrfToken}"/>
        <table class="bridge-kv">
            <tr>
                <th><label for="set-apiBase">API base override</label></th>
                <td>
                    <input type="text" id="set-apiBase" name="apiBase" value="<c:out value='${set_apiBase}'/>" placeholder="(derive per connection)" style="width:360px;"/>
                    <div style="font-size:11px;color:#888;">Blank = derive from each connection's GitHub URL (github.com &rarr; api.github.com, GHE &rarr; &lt;host&gt;/api/v3).</div>
                </td>
            </tr>
            <tr>
                <th><label for="set-apiVersion">API version</label></th>
                <td>
                    <input type="text" id="set-apiVersion" name="apiVersion" value="<c:out value='${set_apiVersion}'/>" placeholder="2022-11-28" style="width:160px;"/>
                    <div style="font-size:11px;color:#888;">The <code>X-GitHub-Api-Version</code> header, e.g. <code>2022-11-28</code>.</div>
                </td>
            </tr>
            <tr>
                <th><label for="set-ttl">PR-info cache TTL (s)</label></th>
                <td><input type="number" min="0" id="set-ttl" name="ttlSeconds" value="${set_ttlSeconds}" style="width:100px;"/></td>
            </tr>
            <tr>
                <th><label for="set-grace">Stale grace (s)</label></th>
                <td>
                    <input type="number" min="0" id="set-grace" name="staleGraceSeconds" value="${set_staleGraceSeconds}" style="width:100px;"/>
                    <div style="font-size:11px;color:#888;">How long a stale PR-info entry may still be served when a refresh fetch fails.</div>
                </td>
            </tr>
            <tr>
                <th><label for="set-attempts">HTTP retry attempts</label></th>
                <td><input type="number" min="1" max="10" id="set-attempts" name="httpMaxAttempts" value="${set_httpMaxAttempts}" style="width:80px;"/> &nbsp; base delay (ms) <input type="number" min="0" name="httpBaseDelayMs" value="${set_httpBaseDelayMs}" style="width:100px;"/></td>
            </tr>
            <tr>
                <th>Feature flags</th>
                <td>
                    <label style="display:block;"><input type="checkbox" name="replayEnabled" <c:if test="${set_replayEnabled}">checked</c:if>/> Webhook replay protection</label>
                    <label style="display:block;"><input type="checkbox" name="dryRun" <c:if test="${set_dryRun}">checked</c:if>/> Dry-run (log intended actions, perform none)</label>
                    <label style="display:block;"><input type="checkbox" name="metricsEnabled" <c:if test="${set_metricsEnabled}">checked</c:if>/> Metrics endpoint</label>
                    <label style="display:block;"><input type="checkbox" name="legacyAliases" <c:if test="${set_legacyAliases}">checked</c:if>/> Publish legacy <code>teamcity.pullRequest.*</code> aliases</label>
                    <label style="display:block;"><input type="checkbox" name="branchPrLookup" <c:if test="${set_branchPrLookup}">checked</c:if>/> Attach branch builds to their PR <span style="color:#888;">(look the PR up from the built commit, for builds launched on a plain branch)</span></label>
                    <label style="display:block;"><input type="checkbox" name="mergeBase" <c:if test="${set_mergeBase}">checked</c:if>/> Resolve the pull request's merge base <span style="color:#888;">(fills <code>&hellip;pullRequest.mergeBase</code>, the only correct base for a &quot;what did this PR change&quot; diff &mdash; the target branch's head also carries everything that landed on it since. One <code>compare</code> call per cache fill, not per build.)</span></label>
                    <label style="display:block;"><input type="checkbox" name="prTabChangedFiles" <c:if test="${set_prTabChangedFiles}">checked</c:if>/> List the changed files on the build's <em>Pull request</em> tab <span style="color:#888;">(from the same cached <code>compare</code> call; a cold cache costs one call, and only because somebody opened the tab. Off = opening a build page never talks to GitHub.)</span></label>
                    <label style="display:block;"><input type="checkbox" name="rerunAllOnlyFailed" <c:if test="${set_rerunAllOnlyFailed}">checked</c:if>/> "Re-run all checks" re-runs only the failed ones <span style="color:#888;">(off = re-run every opted-in build configuration for that commit)</span></label>
                    <label style="display:block;"><input type="checkbox" name="artifactLinks" <c:if test="${set_artifactLinks}">checked</c:if>/> List artifacts in the Check Run <span style="color:#888;">(one click from the PR to the installer/package)</span></label>
                    <label style="display:block;"><input type="checkbox" name="annotations" <c:if test="${set_annotations}">checked</c:if>/> Annotate the diff with compiler diagnostics <span style="color:#888;">(pin errors/warnings to their file and line in the pull request. This switch is the <strong>server-wide</strong> one of three: a project (its <em>GitHub Bridge</em> tab) and a build configuration (its <em>GitHub Bridge integration</em> feature) can each say no as well, and <strong>one &quot;no&quot; anywhere wins</strong> &mdash; ticking it here does not overrule them.)</span></label>
                    <label style="display:block;margin-left:1.5em;"><input type="checkbox" name="annotationLogScan" <c:if test="${set_annotationLogScan}">checked</c:if>/> &hellip; reading the build log when the build problems carry none <span style="color:#888;">(a <strong>Command Line</strong> runner &mdash; how most CMake/ninja builds are wired &mdash; reports only &quot;Process exited with code 1&quot;, and the compiler output never becomes a build problem; without this, annotations do nothing for those builds. Failed builds only, and the read stops at the 50th annotation or 200&nbsp;000 lines.)</span></label>
                    <label style="display:block;"><input type="checkbox" name="testStats" <c:if test="${set_testStats}">checked</c:if>/> Report the test outcome <span style="color:#888;">(counts in the Check Run title GitHub shows in the merge box, failing tests in the body with new failures first and muted ones counted apart)</span></label>
                    <label style="display:block;"><input type="checkbox" name="timings" <c:if test="${set_timings}">checked</c:if>/> Report the build's timings <span style="color:#888;">(total, working time, total wait, and the causes of the wait: its dependencies, and what TeamCity itself blames on there being no free agent &mdash; wait it cannot explain is counted but not named; also sends <code>started_at</code>/<code>completed_at</code> so GitHub shows the duration itself)</span></label>
                    <label style="display:block;"><input type="checkbox" name="infraNeutral" <c:if test="${set_infraNeutral}">checked</c:if>/> An infrastructure failure does not block the merge <span style="color:#888;">(a build that broke on the checkout, an artifact dependency or the runner &mdash; not on the code &mdash; concludes <code>neutral</code> instead of <code>failure</code>, which GitHub counts as satisfied for a required check. <strong>Off by default</strong>, deliberately: telling &quot;our CI broke&quot; from &quot;your code is broken&quot; is a subtle call, and this flag turns that call into an unblocked merge. The Check Run title names the suspected cause either way &mdash; that part is always on and costs nothing if the guess is wrong. Turn this on once you trust the classification on your own builds. A failed snapshot dependency stays red regardless, because it may have failed on this PR's code.)</span></label>
                    <label style="display:block;"><input type="checkbox" name="queueCleanup" <c:if test="${set_queueCleanup}">checked</c:if>/> Queue cleanup <span style="color:#888;">(let the bridge remove builds it suppresses: draft PRs, out-of-scope filters, already-passed commits, closed PRs. Off = the plugin never removes or holds a build, only adds and reports. Only ever applies to build configurations carrying the GitHub Bridge feature.)</span></label>
                    <label style="display:block;"><input type="checkbox" name="cancelObsolete" <c:if test="${set_cancelObsolete}">checked</c:if>/> Stop running builds whose result has nowhere to go <span style="color:#888;">(a new commit was pushed to the PR &mdash; the builds still <em>running</em> on the previous head would judge a commit nobody will look at again while holding an agent &mdash; or the PR was closed/merged. Never a personal build, never one somebody started by hand; on a push, never the last build in flight for that branch either. Removing <em>queued</em> builds is <em>Queue cleanup</em> below, which this also requires.)</span></label>
                    <label style="display:block;"><input type="checkbox" name="prTag" <c:if test="${set_prTag}">checked</c:if>/> Tag PR builds with their PR number <span style="color:#888;">(persists the number as a build tag, which is what <strong>TeamCity's own tag filter and search</strong> can find. Not needed for the <em>Branches &amp; PRs</em> tab: since v1.10.0 its PR column also reads the build's published <code>&hellip;pullRequest.number</code> parameter, so the column is filled either way &mdash; including for builds that ran before the tag existed.)</span></label>
                    <label style="display:block; margin-left:1.6em;"><input type="checkbox" name="prTagDisplay" <c:if test="${set_prTagDisplay}">checked</c:if>/> &hellip; and show it in build lists <span style="color:#888;">(TeamCity's Tags column is narrow: a second tag on a build makes it collapse to a count, and the <code>draft</code>/<code>ready</code> pill &mdash; the one worth seeing at a glance &mdash; stops being legible. Untick to <strong>keep the tag but hide its chip</strong>: still searchable, no longer competing for the column. Client-side, like the pill colouring.)</span></label>
                    <label style="display:block; margin-left:1.6em;">PR tag prefix: <input type="text" name="prTagPrefix" size="10" value="<c:out value="${set_prTagPrefix}"/>"/> <span style="color:#888;">(default <code>pr-</code>, giving <code>pr-189</code>; no spaces)</span></label>
                </td>
            </tr>
            <tr>
                <th><label for="set-allowlist">Repository allowlist</label></th>
                <td>
                    <textarea id="set-allowlist" name="repoAllowlist" rows="3" style="width:360px;font-family:monospace;"><c:out value="${set_repoAllowlist}"/></textarea>
                    <div style="font-size:11px;color:#888;">One <code>owner/name</code> per line. Empty = act on all repositories.</div>
                </td>
            </tr>
            <tr>
                <th><label for="set-assoc">Comment-trigger authors</label></th>
                <td>
                    <input type="text" id="set-assoc" name="commentAssociations" value="<c:out value='${set_commentAssociations}'/>" style="width:360px;"/>
                    <div style="font-size:11px;color:#888;">GitHub <code>author_association</code> values allowed to trigger builds via PR comments (comma-separated). Default <code>OWNER,MEMBER,COLLABORATOR</code>.</div>
                </td>
            </tr>
            <tr>
                <td></td>
                <td><button type="submit" style="padding:6px 16px; background:#1976d2; color:#fff; border:none; border-radius:3px; font-weight:600; cursor:pointer;">Save server settings</button></td>
            </tr>
        </table>
    </form>
</div>

<div class="bridge-section bridge-card">
    <h2>External API</h2>
    <p style="color:#555; font-size:13px; margin-top:0;">
        A bearer token enables the authenticated API under
        <code>/app/teamcity-github-bridge/api/</code> (status, events, metrics,
        and build trigger). No token = API disabled. Pass it as
        <code>Authorization: Bearer &lt;token&gt;</code>.
    </p>
    <c:choose>
        <c:when test="${apiTokenConfigured}">
            <span class="bridge-status bridge-ok">enabled</span>
        </c:when>
        <c:otherwise>
            <span class="bridge-status bridge-warn">disabled</span>
        </c:otherwise>
    </c:choose>
    <form class="bridge-secret-form" method="post" action="${saveSecretUrl}" autocomplete="off">
        <label for="bridge-apitoken-input">
            <c:choose>
                <c:when test="${apiTokenConfigured}">Replace the API token:</c:when>
                <c:otherwise>Set an API token to enable the API:</c:otherwise>
            </c:choose>
        </label>
        <input id="bridge-apitoken-input" type="password" name="apiToken" placeholder="paste a strong random string" autocomplete="new-password" required/>
        <input type="hidden" name="action" value="setApiToken"/>
        <input type="hidden" name="${csrfTokenName}" value="${csrfToken}"/>
        <button type="submit">Save</button>
        <div class="bridge-hint">Generate with <code>openssl rand -hex 32</code>. Stored alongside the other plugin settings; never echoed back.</div>
    </form>
    <c:if test="${apiTokenConfigured}">
        <form method="post" action="${saveSecretUrl}" style="margin-top:6px;"
              onsubmit="return confirm('Clear the API token? The external API will be disabled until a new one is set.');">
            <input type="hidden" name="action" value="clearApiToken"/>
            <input type="hidden" name="${csrfTokenName}" value="${csrfToken}"/>
            <button type="submit" style="color:#b71c1c; background:transparent; border:1px solid #b71c1c; padding:4px 10px; border-radius:3px;">Disable API</button>
        </form>
    </c:if>
</div>

<div class="bridge-section bridge-card">
    <h2>Self-tests</h2>
    <form class="bridge-runtests-form" method="post" action="${runTestsUrl}">
        <input type="hidden" name="${csrfTokenName}" value="${csrfToken}"/>
        <button type="submit">Run self-tests</button>
        <span style="color: #555; font-size: 13px;">
            Run this <strong>after</strong> setting the webhook secret and creating the App. Checks config,
            GitHub reachability, HMAC roundtrip, webhook self-delivery, and token resolution for every opted-in build configuration.
        </span>
    </form>
</div>

<div class="bridge-section">
    <h2>Recent events <small style="color: #888;">(last ${fn:length(recentEvents)} in-memory; full history in the dedicated log)</small></h2>
    <c:choose>
        <c:when test="${empty recentEvents}">
            <p style="color: #888; font-style: italic;">
                No webhook deliveries yet. Configure the webhook in your GitHub App
                and trigger a ping.
            </p>
        </c:when>
        <c:otherwise>
            <table class="bridge-events">
                <thead>
                    <tr>
                        <th>Time</th>
                        <th>Event</th>
                        <th>Action</th>
                        <th>Repository</th>
                        <th>Status</th>
                        <th>Outcome</th>
                        <th>Detail</th>
                    </tr>
                </thead>
                <tbody>
                    <c:forEach var="e" items="${recentEvents}">
                        <tr>
                            <td><code>${e.timestamp}</code></td>
                            <td><code>${e.event}</code></td>
                            <td><code>${e.action}</code></td>
                            <td><code>${e.repo}</code></td>
                            <td>${e.httpStatus}</td>
                            <td class="${e.outcomeClass}">${e.outcome}</td>
                            <td>${e.detail}</td>
                        </tr>
                    </c:forEach>
                </tbody>
            </table>
        </c:otherwise>
    </c:choose>
</div>

<div class="bridge-section bridge-card">
    <h2>GitHub App webhook quick-config</h2>
    <p>Copy these into the App's webhook page (<code>https://github.com/settings/apps/&lt;your-app&gt;</code> -> Webhook).</p>
    <table class="bridge-kv">
        <tr><th>Payload URL</th><td><span class="bridge-copy">${webhookUrl}</span></td></tr>
        <tr><th>Content type</th><td><code>application/json</code></td></tr>
        <tr><th>Secret</th><td>same value as <code>teamcity.github.bridge.webhook.secret</code></td></tr>
        <tr><th>SSL verification</th><td>Enable</td></tr>
        <tr>
            <th>Subscribe to events</th>
            <td>
                <ul style="margin: 0; padding-left: 18px;">
                    <c:forEach var="ev" items="${recommendedEvents}">
                        <li><code>${ev}</code></li>
                    </c:forEach>
                </ul>
            </td>
        </tr>
    </table>
</div>

<div class="bridge-help">
    <h3>What this plugin does</h3>
    <ul>
        <li><strong>Holds builds for draft PRs</strong> (<code>StartBuildPrecondition</code>) - no agent time wasted.</li>
        <li><strong>Tags held PRs</strong> with <code>draft</code> or <code>ready</code> the moment they hit the queue, rendered as coloured pills in build lists.</li>
        <li><strong>Posts GitHub Check Runs</strong> at every lifecycle event (skipped, in progress, completed) carrying the build's actual status text - the bundled <code>commitStatusPublisher</code>'s hard-coded message stays as a fallback.</li>
        <li><strong>Retriggers builds</strong> automatically when a PR transitions from draft to ready for review.</li>
        <li><strong>Single App-level webhook</strong> with HMAC-SHA256 verification - no per-repo webhooks to maintain.</li>
    </ul>

    <h3 style="margin-top: 16px;">Documentation</h3>
    <ul>
        <li><a href="https://github.com/silmaen/teamcity-github" target="_blank">Project README (GitHub)</a></li>
        <li><a href="https://github.com/silmaen/teamcity-github/blob/main/doc/installation.md" target="_blank">Installation</a></li>
        <li><a href="https://github.com/silmaen/teamcity-github/blob/main/doc/github-app-setup.md" target="_blank">GitHub App setup</a></li>
        <li><a href="https://github.com/silmaen/teamcity-github/blob/main/doc/webhook-setup.md" target="_blank">Webhook setup</a></li>
        <li><a href="https://github.com/silmaen/teamcity-github/blob/main/doc/configuration.md" target="_blank">Configuration reference</a></li>
        <li><a href="https://github.com/silmaen/teamcity-github/blob/main/doc/usage-scenarios.md" target="_blank">Usage scenarios</a></li>
        <li><a href="https://github.com/silmaen/teamcity-github/blob/main/doc/architecture.md" target="_blank">Architecture</a></li>
        <li><a href="https://github.com/silmaen/teamcity-github/blob/main/doc/security.md" target="_blank">Security model</a></li>
        <li><a href="https://github.com/silmaen/teamcity-github/blob/main/doc/troubleshooting.md" target="_blank">Troubleshooting</a></li>
        <li><a href="https://github.com/silmaen/teamcity-github/blob/main/doc/api-reference.md" target="_blank">HTTP API reference</a></li>
        <li><a href="https://github.com/silmaen/teamcity-github/blob/main/doc/roadmap.md" target="_blank">Roadmap</a></li>
    </ul>

    <details class="bridge-fold" style="margin-top: 16px;">
        <summary>Enable on a build configuration</summary>
        <p>Add these three parameters to the build configuration (or a shared template) under <code>Parameters</code>:</p>
        <table class="bridge-kv">
            <tr><th><code>teamcity.github.bridge.ignoreDrafts</code></th><td><code>true</code></td></tr>
            <tr><th><code>teamcity.github.bridge.repo</code></th><td><code>owner/name</code> (e.g. <code>acme/widget</code>)</td></tr>
            <tr><th><code>teamcity.github.bridge.connectionId</code></th><td>The connection ID of the GitHub App connection (e.g. <code>PROJECT_EXT_42</code>)</td></tr>
        </table>
        <p>That is the only opt-in. Build types without these parameters are untouched by the plugin.</p>
    </details>

    <details class="bridge-fold" style="margin-top: 12px;">
        <summary>GitHub Check Runs and the bundled Commit Status Publisher</summary>
        <p>
            The plugin posts rich <strong>Check Runs</strong> on every
            lifecycle event of an opted-in build. The TC-bundled
            <code>commitStatusPublisher</code> keeps posting
            <strong>Commit Statuses</strong> with its hard-coded
            <code>"TeamCity build finished"</code> message until you
            disable it.
        </p>
        <p>Two operating modes:</p>
        <ul>
            <li><strong>Keep both</strong>: configure branch protection to require only the Check Run names (typically <code>TeamCity / &lt;buildType full name&gt;</code>). The Commit Statuses appear in the PR as informational.</li>
            <li><strong>Disable the bundled publisher</strong> on opted-in build types via <code>Edit Configuration -&gt; Build Features -&gt; Commit status publisher -&gt; Disable</code>. The plugin's Check Runs become the single source.</li>
        </ul>
    </details>

    <details class="bridge-fold" style="margin-top: 12px;">
        <summary>Enable the dedicated log file</summary>
        <p>By default plugin entries are mixed into <code>teamcity-server.log</code>. To route them to <code>${logFile}</code>, merge the snippet shipped with the plugin (resource <code>/${snippetResourceName}</code>) into <code>&lt;TC_DATA_DIR&gt;/config/teamcity-server-log4j.xml</code>. TC hot-reloads log4j; no restart needed.</p>
    </details>

    <details class="bridge-fold" style="margin-top: 12px;">
        <summary>Common 401 / 404 troubleshooting</summary>
        <ul>
            <li><strong>401 Invalid signature</strong>: secret on TC and on GitHub differ. Re-paste both sides; values must match byte for byte (no leading or trailing whitespace).</li>
            <li><strong>401 Authentication required</strong>: outdated plugin build. Upgrade to a release that registers <code>AuthorizationInterceptor.addPathNotRequiringAuth</code>.</li>
            <li><strong>404 Not Found</strong>: reverse proxy strips <code>/app/...</code> or plugin not loaded. Curl from the TC host directly to bypass the proxy.</li>
            <li><strong>Webhook URL shows http:// in /info</strong>: reverse proxy not forwarding <code>X-Forwarded-Proto</code>. Configure nginx <code>proxy_set_header X-Forwarded-Proto $scheme</code>.</li>
            <li><strong>Two TC entries on PR (Commit Status + Check Run)</strong>: expected; see the section above on coexistence with the bundled publisher.</li>
        </ul>
    </details>
</div>
