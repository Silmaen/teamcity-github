package io.github.dlachouette.teamcity.github.config

import jetbrains.buildServer.serverSide.ServerPaths
import java.io.File

// Resolves the path of the dedicated log file and reports how it was
// wired up. As of v0.6.0 the plugin attaches the appender itself at
// startup via `PluginLogConfigurator`, so the file is normally
// auto-configured. The legacy log4j snippet remains shipped for
// operators who want to override the routing manually.
class LogPathResolver(
    private val serverPaths: ServerPaths,
    private val logConfigurator: PluginLogConfigurator,
) {

    fun expectedFile(): File = File(serverPaths.logsPath, FILE_NAME)

    // True when the dedicated file is wired up - either the plugin's
    // own appender is attached (the common case), or an operator
    // already attached one through teamcity-server-log4j.xml and we
    // detected it.
    fun isConfigured(): Boolean = when (logConfigurator.state()) {
        PluginLogConfigurator.State.AUTO_CONFIGURED,
        PluginLogConfigurator.State.OPERATOR_CONFIGURED -> true
        PluginLogConfigurator.State.FAILED -> expectedFile().exists()
    }

    fun stateLabel(): String = when (logConfigurator.state()) {
        PluginLogConfigurator.State.AUTO_CONFIGURED -> "auto-configured"
        PluginLogConfigurator.State.OPERATOR_CONFIGURED -> "operator-configured"
        PluginLogConfigurator.State.FAILED -> if (expectedFile().exists()) "file present (config unknown)" else "not configured"
    }

    companion object {
        const val FILE_NAME: String = "teamcity-github-bridge.log"
        const val SNIPPET_RESOURCE: String = "/teamcity-github-bridge-log4j-snippet.xml"
    }
}
