<%--
  TeamCity GitHub Bridge - admin console JSP.
  Rendered as a tab under Administration -> Server Administration.
  Model attributes are populated by AdminConsolePage.fillModel.
--%>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="fn" uri="http://java.sun.com/jsp/jstl/functions" %>

<style>
    .tcgh-section { margin: 0 0 24px 0; }
    .tcgh-section h2 { margin: 0 0 8px 0; }
    .tcgh-status { padding: 6px 10px; border-radius: 4px; font-weight: 600; display: inline-block; }
    .tcgh-ok { background: #e8f5e9; color: #1b5e20; }
    .tcgh-warn { background: #fff8e1; color: #ad8400; }
    .tcgh-bad { background: #ffebee; color: #b71c1c; }
    .tcgh-card { border: 1px solid #ddd; border-radius: 6px; padding: 16px; background: #fafafa; }
    .tcgh-kv { width: 100%; border-collapse: collapse; }
    .tcgh-kv th { text-align: left; padding: 6px 8px; width: 28%; vertical-align: top; color: #555; font-weight: 500; }
    .tcgh-kv td { padding: 6px 8px; vertical-align: top; }
    .tcgh-copy { font-family: monospace; background: #fff; border: 1px solid #ddd; border-radius: 3px; padding: 2px 6px; }
    .tcgh-events { width: 100%; border-collapse: collapse; font-size: 13px; }
    .tcgh-events th, .tcgh-events td { border-bottom: 1px solid #eee; padding: 6px 8px; text-align: left; vertical-align: top; }
    .tcgh-events th { background: #f0f0f0; }
    .tcgh-accepted { color: #1b5e20; font-weight: 600; }
    .tcgh-skipped { color: #ad8400; font-weight: 600; }
    .tcgh-rejected { color: #b71c1c; font-weight: 600; }
    .tcgh-help { background: #f5f5f5; border-radius: 6px; padding: 16px; margin-top: 24px; }
    .tcgh-help h3 { margin-top: 0; }
    .tcgh-help ul { margin: 4px 0 0 18px; padding: 0; }
    details.tcgh-fold > summary { cursor: pointer; font-weight: 600; padding: 4px 0; }
    pre.tcgh-snippet { background: #f7f7f7; border: 1px solid #ddd; padding: 12px; overflow-x: auto; font-size: 12px; }
    .tcgh-banner { padding: 10px 14px; border-radius: 6px; margin: 0 0 16px 0; font-weight: 500; }
    .tcgh-banner-ok { background: #e8f5e9; color: #1b5e20; border: 1px solid #a5d6a7; }
    .tcgh-banner-warn { background: #fff8e1; color: #ad8400; border: 1px solid #ffe082; }
    .tcgh-banner-bad { background: #ffebee; color: #b71c1c; border: 1px solid #ef9a9a; }
    form.tcgh-secret-form { margin: 12px 0 0 0; padding: 12px; background: #fff; border: 1px solid #ddd; border-radius: 4px; }
    form.tcgh-secret-form label { display: block; margin: 0 0 6px 0; font-size: 12px; color: #555; }
    form.tcgh-secret-form input[type=password] { width: 360px; padding: 5px 8px; font-family: monospace; border: 1px solid #ccc; border-radius: 3px; }
    form.tcgh-secret-form button { margin-left: 6px; padding: 5px 12px; }
    form.tcgh-secret-form .tcgh-hint { font-size: 11px; color: #888; margin-top: 6px; }
</style>

<h1 style="margin-top: 0;">TeamCity GitHub Bridge</h1>
<p>
    Server-side plugin that closes the gap between TeamCity and GitHub:
    draft PR awareness, automatic retrigger on ready-for-review,
    App-level webhooks with HMAC verification.
</p>

<c:if test="${not empty resultBanner}">
    <div class="tcgh-banner tcgh-banner-${resultBanner.level}">
        ${resultBanner.text}
    </div>
</c:if>

<div class="tcgh-section tcgh-card">
    <h2>Plugin status</h2>
    <table class="tcgh-kv">
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
            <td><span class="tcgh-copy">${webhookUrl}</span></td>
        </tr>
        <tr>
            <th>HMAC secret</th>
            <td>
                <c:choose>
                    <c:when test="${secretConfigured}">
                        <span class="tcgh-status tcgh-ok">configured</span>
                        <c:choose>
                            <c:when test="${secretSource == 'PLUGIN_SETTINGS'}">
                                &nbsp;<small>(via this page)</small>
                            </c:when>
                            <c:when test="${secretSource == 'INTERNAL_PROPERTIES'}">
                                &nbsp;<small>(via <code>tcgh.webhook.secret</code> in <code>internal.properties</code> - legacy)</small>
                            </c:when>
                        </c:choose>
                    </c:when>
                    <c:otherwise>
                        <span class="tcgh-status tcgh-bad">NOT configured</span>
                        &nbsp;Webhooks will be rejected with 401 until a secret is set.
                    </c:otherwise>
                </c:choose>

                <form class="tcgh-secret-form" method="post" action="${saveSecretUrl}" autocomplete="off">
                    <label for="tcgh-secret-input">
                        <c:choose>
                            <c:when test="${secretConfigured}">Replace the secret:</c:when>
                            <c:otherwise>Set the secret:</c:otherwise>
                        </c:choose>
                    </label>
                    <input id="tcgh-secret-input" type="password" name="secret" placeholder="paste a strong random string" autocomplete="new-password" required/>
                    <input type="hidden" name="action" value="set"/>
                    <button type="submit">Save</button>
                    <div class="tcgh-hint">
                        Stored in <code>&lt;TC_DATA_DIR&gt;/config/teamcity-github-bridge.properties</code>.
                        The value is never echoed back; rotate it by submitting a new one.
                        Generate with <code>openssl rand -hex 48</code>.
                    </div>
                </form>
                <c:if test="${secretConfigured}">
                    <form method="post" action="${saveSecretUrl}" style="margin-top: 6px;"
                          onsubmit="return confirm('Clear the webhook secret? Every webhook delivery will be rejected with 401 until a new one is set.');">
                        <input type="hidden" name="action" value="clear"/>
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
                        <span class="tcgh-status tcgh-ok">${logStateLabel}</span>
                        &nbsp;<code>${logFile}</code>
                    </c:when>
                    <c:otherwise>
                        <span class="tcgh-status tcgh-warn">${logStateLabel}</span>
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

<div class="tcgh-section">
    <h2>Recent events <small style="color: #888;">(last ${fn:length(recentEvents)} in-memory; full history in the dedicated log)</small></h2>
    <c:choose>
        <c:when test="${empty recentEvents}">
            <p style="color: #888; font-style: italic;">
                No webhook deliveries yet. Configure the webhook in your GitHub App
                and trigger a ping.
            </p>
        </c:when>
        <c:otherwise>
            <table class="tcgh-events">
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

<div class="tcgh-section tcgh-card">
    <h2>GitHub App webhook quick-config</h2>
    <p>Copy these into the App's webhook page (<code>https://github.com/settings/apps/&lt;your-app&gt;</code> -> Webhook).</p>
    <table class="tcgh-kv">
        <tr><th>Payload URL</th><td><span class="tcgh-copy">${webhookUrl}</span></td></tr>
        <tr><th>Content type</th><td><code>application/json</code></td></tr>
        <tr><th>Secret</th><td>same value as <code>tcgh.webhook.secret</code></td></tr>
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

<div class="tcgh-help">
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

    <details class="tcgh-fold" style="margin-top: 16px;">
        <summary>Enable on a build configuration</summary>
        <p>Add these three parameters to the build configuration (or a shared template) under <code>Parameters</code>:</p>
        <table class="tcgh-kv">
            <tr><th><code>tcgh.ignoreDrafts</code></th><td><code>true</code></td></tr>
            <tr><th><code>tcgh.github.repo</code></th><td><code>owner/name</code> (e.g. <code>Silmaen/Owl</code>)</td></tr>
            <tr><th><code>tcgh.github.connectionId</code></th><td>The connection ID of the GitHub App connection (e.g. <code>PROJECT_EXT_42</code>)</td></tr>
        </table>
        <p>That is the only opt-in. Build types without these parameters are untouched by the plugin.</p>
    </details>

    <details class="tcgh-fold" style="margin-top: 12px;">
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

    <details class="tcgh-fold" style="margin-top: 12px;">
        <summary>Enable the dedicated log file</summary>
        <p>By default plugin entries are mixed into <code>teamcity-server.log</code>. To route them to <code>${logFile}</code>, merge the snippet shipped with the plugin (resource <code>/${snippetResourceName}</code>) into <code>&lt;TC_DATA_DIR&gt;/config/teamcity-server-log4j.xml</code>. TC hot-reloads log4j; no restart needed.</p>
    </details>

    <details class="tcgh-fold" style="margin-top: 12px;">
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
