package io.github.dlachouette.teamcity.github.config

import com.intellij.openapi.diagnostic.Logger
import io.github.dlachouette.teamcity.github.feature.BundledPublisherDetector
import jetbrains.buildServer.serverSide.BuildServerAdapter
import jetbrains.buildServer.serverSide.ProjectManager
import jetbrains.buildServer.serverSide.SBuildServer

// Warns, once per server start, about build configurations that carry both the
// GitHub Bridge feature and TeamCity's bundled Commit status publisher.
//
// Once per start and not once per build: the message is about configuration,
// and a per-build warning would flood the log without telling the operator
// anything new. The same list is available on demand through the admin page's
// self-tests.
class PublisherConflictReporter(
    buildServer: SBuildServer,
    private val projectManager: ProjectManager,
) : BuildServerAdapter() {

    init {
        buildServer.addListener(this)
    }

    override fun serverStartup() {
        try {
            val offenders = BundledPublisherDetector.scan(projectManager.allBuildTypes)
            if (offenders.isEmpty()) return
            LOG.warn(
                "${offenders.size} build configuration(s) carry both the GitHub Bridge feature and " +
                    "TeamCity's bundled Commit status publisher, so GitHub will show two competing rows " +
                    "per build: ${offenders.take(MAX_LISTED).joinToString(", ")}" +
                    (if (offenders.size > MAX_LISTED) " (+${offenders.size - MAX_LISTED} more)" else "") +
                    ". Disable the bundled publisher on them — the bridge never does it for you."
            )
        } catch (e: Exception) {
            LOG.debug("Could not scan for bundled-publisher conflicts: ${e.message}")
        }
    }

    companion object {
        private val LOG = Logger.getInstance(PublisherConflictReporter::class.java.name)

        private const val MAX_LISTED: Int = 20
    }
}
