package io.github.dlachouette.teamcity.github.config

import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.serverSide.ServerPaths
import org.apache.log4j.PatternLayout
import org.apache.log4j.RollingFileAppender
import java.io.File

// Attaches a dedicated log4j appender to the plugin's package logger
// at startup, routing all `io.github.dlachouette.teamcity.github.*`
// output to <TC_DATA_DIR>/logs/teamcity-github-bridge.log with size-
// based rotation (10 MB per file, 10 historical files = ~100 MB
// retention).
//
// Idempotent: re-creating the bean reuses the existing appender
// (matched by name).
// Respectful: if the operator already attached an appender via a
// manual log4j config edit, we leave their configuration alone.
class PluginLogConfigurator(
    private val serverPaths: ServerPaths,
) {
    private val state: State

    init {
        state = configure()
        STARTUP_LOG.info("Plugin log configurator state: $state, target=${logFile().absolutePath}")
    }

    fun logFile(): File = File(serverPaths.logsPath, FILE_NAME)

    fun state(): State = state

    private fun configure(): State {
        return try {
            val pkgLogger = org.apache.log4j.Logger.getLogger(PACKAGE)
            val existingOurAppender = pkgLogger.getAppender(APPENDER_NAME)
            val hasOtherAppender = pkgLogger.allAppenders
                .asSequence()
                .filterNotNull()
                .any { (it as? org.apache.log4j.Appender)?.name != APPENDER_NAME }
            when {
                existingOurAppender != null -> State.AUTO_CONFIGURED
                hasOtherAppender -> State.OPERATOR_CONFIGURED
                else -> {
                    val appender = RollingFileAppender(
                        PatternLayout("[%d{ISO8601}] %-5p %c{1} - %m%n"),
                        logFile().absolutePath,
                        true, // append
                    )
                    appender.name = APPENDER_NAME
                    appender.setMaxFileSize("10MB")
                    appender.maxBackupIndex = 10
                    pkgLogger.addAppender(appender)
                    pkgLogger.additivity = false
                    State.AUTO_CONFIGURED
                }
            }
        } catch (e: Throwable) {
            STARTUP_LOG.warn("Failed to auto-configure dedicated log file: ${e.message}", e)
            State.FAILED
        }
    }

    enum class State { AUTO_CONFIGURED, OPERATOR_CONFIGURED, FAILED }

    companion object {
        const val FILE_NAME: String = "teamcity-github-bridge.log"
        const val PACKAGE: String = "io.github.dlachouette.teamcity.github"
        const val APPENDER_NAME: String = "BRIDGE_AUTO"

        private val STARTUP_LOG = Logger.getInstance(PluginLogConfigurator::class.java.name)
    }
}
