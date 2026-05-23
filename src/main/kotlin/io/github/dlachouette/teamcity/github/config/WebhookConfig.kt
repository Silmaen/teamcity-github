package io.github.dlachouette.teamcity.github.config

import jetbrains.buildServer.serverSide.TeamCityProperties

class WebhookConfig {

    fun secret(): String? {
        val raw = TeamCityProperties.getProperty(SECRET_PROPERTY)
        return raw.takeIf { it.isNotBlank() }
    }

    fun isSecretConfigured(): Boolean = secret() != null

    companion object {
        const val SECRET_PROPERTY: String = "tcgh.webhook.secret"
        const val SIGNATURE_HEADER: String = "X-Hub-Signature-256"
        const val SIGNATURE_ALGORITHM: String = "HmacSHA256"
        const val SIGNATURE_PREFIX: String = "sha256="
    }
}
