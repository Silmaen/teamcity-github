package io.github.dlachouette.teamcity.github.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.diagnostic.Logger
import io.github.dlachouette.teamcity.github.config.BridgeServerSettings
import io.github.dlachouette.teamcity.github.config.PluginSettingsStorage
import java.time.Clock

// Provisioning + verification of the plugin-managed GitHub App.
//
//  - buildManifest(): the JSON for GitHub's App-manifest creation flow,
//    pre-filled with this server's webhook URL and the exact
//    permissions/events the plugin needs.
//  - storeConversion(): persist the credentials GitHub returns after the
//    operator confirms creation (app id, private key, slug, webhook
//    secret) into the plugin settings.
//  - verify(): authenticate as the managed App, fetch GET /app, and diff
//    its live permissions/events against what the plugin requires.
class AppManager(
    private val gitHubClient: GitHubClient,
    private val serverSettings: BridgeServerSettings,
    private val storage: PluginSettingsStorage,
) {
    var clock: Clock = Clock.systemUTC()

    private fun apiBase(): String = serverSettings.apiBaseOverride() ?: GitHubClient.DEFAULT_API_BASE

    // Build the App manifest. `name` must be globally unique on GitHub.
    fun buildManifest(name: String, webhookUrl: String, redirectUrl: String): String {
        val root = MAPPER.createObjectNode()
        root.put("name", name)
        root.put("url", redirectUrl)
        root.put("redirect_url", redirectUrl)
        root.put("public", false)
        val hook = root.putObject("hook_attributes")
        hook.put("url", webhookUrl)
        hook.put("active", true)
        val perms = root.putObject("default_permissions")
        REQUIRED_PERMISSIONS.forEach { (k, v) -> perms.put(k, v) }
        val events = root.putArray("default_events")
        REQUIRED_EVENTS.forEach { events.add(it) }
        return MAPPER.writeValueAsString(root)
    }

    // Exchange the manifest code and persist the resulting credentials.
    fun storeConversion(code: String): ManifestConversion? {
        val conversion = gitHubClient.convertManifest(code, apiBase()) ?: return null
        storage.set(BridgeServerSettings.KEY_APP_ID, conversion.appId)
        storage.set(BridgeServerSettings.KEY_APP_PRIVATE_KEY, conversion.pem)
        storage.set(BridgeServerSettings.KEY_APP_SLUG, conversion.slug)
        conversion.webhookSecret?.let { storage.set(PluginSettingsStorage.KEY_SECRET, it) }
        LOG.info("Stored managed GitHub App #${conversion.appId} (slug=${conversion.slug}) from manifest conversion")
        return conversion
    }

    // Verify the managed App's live configuration. Returns null when no
    // managed App is configured or when GitHub could not be reached.
    fun verify(): AppVerification? {
        val appId = serverSettings.managedAppId() ?: return null
        val pem = serverSettings.managedAppPrivateKey() ?: return null
        val key = RsaKeyParser.parsePrivateKey(pem) ?: return AppVerification(
            reachable = false, slug = serverSettings.managedAppSlug(),
            missingPermissions = emptyList(), missingEvents = emptyList(),
            detail = "stored private key could not be parsed",
        )
        val jwt = AppJwt.sign(appId, key, clock) ?: return AppVerification(
            reachable = false, slug = serverSettings.managedAppSlug(),
            missingPermissions = emptyList(), missingEvents = emptyList(),
            detail = "could not sign App JWT",
        )
        val app = gitHubClient.getApp(jwt, apiBase()) ?: return AppVerification(
            reachable = false, slug = serverSettings.managedAppSlug(),
            missingPermissions = emptyList(), missingEvents = emptyList(),
            detail = "GET /app failed (network, or the App was deleted on GitHub)",
        )
        val missingPerms = REQUIRED_PERMISSIONS.filterNot { (k, required) ->
            permissionSatisfied(app.permissions[k], required)
        }.keys.toList()
        val missingEvents = REQUIRED_EVENTS.filterNot { it in app.events }
        return AppVerification(
            reachable = true,
            slug = app.slug,
            missingPermissions = missingPerms,
            missingEvents = missingEvents,
            detail = if (missingPerms.isEmpty() && missingEvents.isEmpty()) "App configuration matches requirements"
            else "App is missing some required permissions/events",
        )
    }

    companion object {
        private val LOG = Logger.getInstance(AppManager::class.java.name)
        private val MAPPER = ObjectMapper()

        // Minimum permissions/events the plugin needs. `pull_requests:write`
        // covers both reading PRs and the optional sticky PR comment.
        val REQUIRED_PERMISSIONS: Map<String, String> = linkedMapOf(
            "metadata" to "read",
            "checks" to "write",
            "pull_requests" to "write",
            "contents" to "read",
        )
        val REQUIRED_EVENTS: List<String> = listOf(
            "pull_request",
            "pull_request_review",
            "issue_comment",
            "check_run",
        )

        // GitHub permission levels are ordered none < read < write < admin.
        private val LEVELS = listOf("none", "read", "write", "admin")

        // Public for testing — true iff `actual` grants at least `required`.
        fun permissionSatisfied(actual: String?, required: String): Boolean {
            val a = LEVELS.indexOf(actual ?: "none")
            val r = LEVELS.indexOf(required)
            return a >= 0 && r >= 0 && a >= r
        }
    }
}

data class AppVerification(
    val reachable: Boolean,
    val slug: String?,
    val missingPermissions: List<String>,
    val missingEvents: List<String>,
    val detail: String,
) {
    val ok: Boolean get() = reachable && missingPermissions.isEmpty() && missingEvents.isEmpty()
}
