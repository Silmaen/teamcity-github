package io.github.dlachouette.teamcity.github.config

import jetbrains.buildServer.serverSide.ServerPaths
import java.io.File

// Resolves the expected dedicated log file path and reports whether
// the operator has actually wired log4j to write to it. The dedicated
// file only appears once the log4j snippet from
// `teamcity-github-bridge-log4j-snippet.xml` is merged into TC's
// `<TC_DATA_DIR>/config/teamcity-server-log4j.xml`.
class LogPathResolver(private val serverPaths: ServerPaths) {

    fun expectedFile(): File = File(serverPaths.logsPath, FILE_NAME)

    fun isConfigured(): Boolean = expectedFile().exists()

    fun snippetResourcePath(): String = SNIPPET_RESOURCE

    companion object {
        const val FILE_NAME: String = "teamcity-github-bridge.log"
        const val SNIPPET_RESOURCE: String = "/teamcity-github-bridge-log4j-snippet.xml"
    }
}
