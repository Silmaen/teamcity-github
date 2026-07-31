package io.github.dlachouette.teamcity.github.report

import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.serverSide.SBuildType

private val LOG = Logger.getInstance("io.github.dlachouette.teamcity.github.report.ReportHelpers")

// THE Check Run name. GitHub dedups Check Run rows by (name, head_sha),
// so this string is load-bearing: every lifecycle event for a build
// must produce the exact same name or GitHub creates a duplicate row.
// Defined once here so a typo at any call site is impossible.
fun checkRunName(buildType: SBuildType): String = "TeamCity / ${buildType.fullName}"

// Evaluate a WebLinks URL accessor, returning null (and logging at
// debug) on any failure or blank result. WebLinks calls can throw when
// the server root URL is not yet configured; a missing details_url is
// never worth failing a Check Run over.
fun safeUrl(block: () -> String?): String? {
    return try {
        block()?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        LOG.debug("WebLinks URL build failed: ${e.message}")
        null
    }
}

// One artefact of a build, as a direct download link.
//
// Lived on `PrSummaryCommenter` until the sticky comment was removed; the Check
// Run's **Artifacts** section is the remaining consumer.
data class ArtifactLink(val name: String, val url: String)
