package io.github.dlachouette.teamcity.github

import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.serverSide.SBuildServer
import jetbrains.buildServer.serverSide.ServerExtension

class TeamCityGitHubBridgePlugin(
    private val buildServer: SBuildServer,
) : ServerExtension {

    init {
        LOG.info("TeamCity GitHub Bridge plugin loaded (build server: ${buildServer.fullServerVersion})")
    }

    companion object {
        const val PLUGIN_NAME: String = "teamcity-github-bridge"
        val LOG: Logger = Logger.getInstance(TeamCityGitHubBridgePlugin::class.java.name)
    }
}
