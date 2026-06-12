package io.github.dlachouette.teamcity.github.web

import com.fasterxml.jackson.databind.ObjectMapper

data class WebhookInfo(
    val payloadUrl: String,
    val contentType: String,
    val sslVerification: Boolean,
    val recommendedEvents: List<String>,
    val secretConfigured: Boolean,
    val logFile: String,
    val logConfigured: Boolean,
    val pluginVersion: String,
) {
    // Jackson handles escaping; the hand-rolled serializer it replaced
    // was an avoidable correctness liability when Jackson is already a
    // dependency used everywhere else in the plugin.
    fun toJson(): String = MAPPER.writeValueAsString(this)

    fun toMarkdown(): String = """
        |# GitHub App Webhook Configuration
        |
        |Configure these values on your GitHub App webhook page
        |(`https://github.com/settings/apps/<your-app>` -> Webhook).
        |
        || Field | Value |
        ||-------|-------|
        || Payload URL | `$payloadUrl` |
        || Content type | `$contentType` |
        || SSL verification | ${if (sslVerification) "Enable" else "Disable"} |
        || Secret | ${if (secretConfigured) "configured server-side - reuse the same value" else "**not configured** - set the `teamcity.github.bridge.webhook.secret` internal property"} |
        |
        |## Subscribe to events
        |
        |${recommendedEvents.joinToString("\n") { "- `$it`" }}
        |
        |## Dedicated log
        |
        |Expected path: `$logFile`
        |Status: ${if (logConfigured) "configured (file exists)" else "**not configured** - merge the log4j snippet shipped with the plugin into `teamcity-server-log4j.xml`"}
        |
        |Plugin version: $pluginVersion
        |""".trimMargin()

    companion object {
        private val MAPPER = ObjectMapper()
    }
}

object WebhookEvents {
    val RECOMMENDED: List<String> = listOf(
        "pull_request",
        "pull_request_review",
        "issue_comment",
        "check_run",
        "push",
        "check_suite",
        "ping",
    )
}
