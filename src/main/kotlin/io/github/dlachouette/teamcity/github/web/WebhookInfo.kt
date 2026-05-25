package io.github.dlachouette.teamcity.github.web

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
    fun toJson(): String = buildString {
        append('{')
        append("\"payloadUrl\":${quote(payloadUrl)},")
        append("\"contentType\":${quote(contentType)},")
        append("\"sslVerification\":$sslVerification,")
        append("\"recommendedEvents\":[")
        append(recommendedEvents.joinToString(",") { quote(it) })
        append("],")
        append("\"secretConfigured\":$secretConfigured,")
        append("\"logFile\":${quote(logFile)},")
        append("\"logConfigured\":$logConfigured,")
        append("\"pluginVersion\":${quote(pluginVersion)}")
        append('}')
    }

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

    private fun quote(s: String): String =
        '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"'
}

object WebhookEvents {
    val RECOMMENDED: List<String> = listOf(
        "pull_request",
        "pull_request_review",
        "push",
        "check_suite",
        "ping",
    )
}
