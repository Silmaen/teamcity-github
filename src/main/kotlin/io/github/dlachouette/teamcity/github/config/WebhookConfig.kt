package io.github.dlachouette.teamcity.github.config

import jetbrains.buildServer.serverSide.TeamCityProperties

// Two sources, checked in order:
//   1. The plugin-owned settings file (written by the admin page form).
//   2. The legacy `teamcity.github.bridge.webhook.secret` key in TC's internal.properties
//      (kept for backwards compatibility with operators who set the
//      secret manually before v0.6.0).
class WebhookConfig(
    private val settingsStorage: PluginSettingsStorage,
) {

    fun secret(): String? {
        settingsStorage.secret()?.let { return it }
        val legacy = TeamCityProperties.getProperty(SECRET_PROPERTY)
        return legacy.takeIf { it.isNotBlank() }
    }

    fun isSecretConfigured(): Boolean = secret() != null

    // Reports which source supplied the secret, so the admin page can
    // explain which knob the operator should turn to change it.
    fun source(): SecretSource = when {
        settingsStorage.secret() != null -> SecretSource.PLUGIN_SETTINGS
        TeamCityProperties.getProperty(SECRET_PROPERTY).isNotBlank() -> SecretSource.INTERNAL_PROPERTIES
        else -> SecretSource.UNSET
    }

    enum class SecretSource { PLUGIN_SETTINGS, INTERNAL_PROPERTIES, UNSET }

    companion object {
        const val SECRET_PROPERTY: String = "teamcity.github.bridge.webhook.secret"
        const val SIGNATURE_HEADER: String = "X-Hub-Signature-256"
    }
}
