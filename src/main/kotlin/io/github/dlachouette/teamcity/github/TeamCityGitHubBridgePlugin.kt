package io.github.dlachouette.teamcity.github

import com.intellij.openapi.diagnostic.Logger
import io.github.dlachouette.teamcity.github.api.GitHubClient
import io.github.dlachouette.teamcity.github.api.PrInfoCache
import io.github.dlachouette.teamcity.github.config.BridgeServerSettings
import jetbrains.buildServer.serverSide.SBuildServer
import jetbrains.buildServer.serverSide.ServerExtension

class TeamCityGitHubBridgePlugin(
    private val buildServer: SBuildServer,
    private val serverSettings: BridgeServerSettings,
    private val gitHubClient: GitHubClient,
    private val prInfoCache: PrInfoCache,
) : ServerExtension {

    init {
        // Push server-global tuning (api version, cache TTL/grace) into the
        // live beans at startup. The admin save controller re-applies on
        // every change so edits take effect without a restart.
        serverSettings.applyTo(gitHubClient, prInfoCache)
        LOG.info("TeamCity GitHub Bridge plugin loaded (build server: ${buildServer.fullServerVersion})")
    }

    companion object {
        val LOG: Logger = Logger.getInstance(TeamCityGitHubBridgePlugin::class.java.name)
    }
}
