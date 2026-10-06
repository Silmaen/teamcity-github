package io.github.dlachouette.teamcity.github.report

import com.intellij.openapi.diagnostic.Logger
import io.github.dlachouette.teamcity.github.feature.BridgeProjectParams
import io.github.dlachouette.teamcity.github.feature.GitHubBridgeBuildFeature
import jetbrains.buildServer.serverSide.SBuildType

private val LOG = Logger.getInstance("io.github.dlachouette.teamcity.github.report.ReportHelpers")

// THE Check Run name. GitHub dedups Check Run rows by (name, head_sha),
// so this string is load-bearing: every lifecycle event for a build
// must produce the exact same name or GitHub creates a duplicate row.
// Defined once here so a typo at any call site is impossible.
//
// A project may shorten it — see `BridgeProjectParams.CHECK_NAME_STRIP_PREFIX`.
// On a deep project tree the full name is mostly ancestry nobody reading a pull
// request needs ("TeamCity / Sandbox / test_ci / PR / Build / Linux / Build
// (Linux, x64, Release)"), and GitHub's merge box truncates what is left.
//
// A build configuration may also fix its name outright — see
// `GitHubBridgeBuildFeature.PARAM_CHECK_NAME` — so that moving it in the
// project tree does not rename the check a branch protection rule requires.
fun checkRunName(buildType: SBuildType): String {
    val custom = try {
        buildType.resolvedSettings
            .getBuildFeaturesOfType(GitHubBridgeBuildFeature.FEATURE_TYPE)
            .firstOrNull()?.parameters?.get(GitHubBridgeBuildFeature.PARAM_CHECK_NAME)
    } catch (e: Exception) {
        LOG.debug("Could not read the check name of ${buildType.externalId}: ${e.message}")
        null
    }
    val strip = try {
        buildType.project.parameters[BridgeProjectParams.CHECK_NAME_STRIP_PREFIX]
    } catch (e: Exception) {
        LOG.debug("Could not read the check-name prefix for ${buildType.externalId}: ${e.message}")
        null
    }
    return resolveCheckRunName(buildType.fullName, custom, strip)
}

// Pure helper: a fixed name wins verbatim (no "TeamCity / ", no stripping);
// otherwise the name derives from the configuration's place in the tree.
fun resolveCheckRunName(buildTypeFullName: String, checkName: String?, stripPrefix: String?): String =
    checkName?.trim()?.takeIf { it.isNotEmpty() }
        ?: stripCheckNamePrefix("$CHECK_NAME_PREFIX$buildTypeFullName", stripPrefix)

const val CHECK_NAME_PREFIX: String = "TeamCity / "

// Pure helper — the whole decision, testable without an SBuildType.
//
// Renaming a Check Run is **not** cosmetic: GitHub keys a row on
// `(name, head_sha)`, and a branch protection rule requires a name literally. So
// the rules here are conservative:
//
//   - the prefix must actually match, or nothing is stripped (a stale setting
//     after a project move silently does nothing rather than mangling the name);
//   - the result must not be blank (stripping the whole name would leave a Check
//     Run with no identity at all);
//   - matching ignores surrounding space so `…/ PR /` and `…/ PR / ` behave the
//     same, which is the mistake everyone makes typing this in a form.
fun stripCheckNamePrefix(fullName: String, stripPrefix: String?): String {
    val prefix = stripPrefix?.trim()?.takeIf { it.isNotEmpty() } ?: return fullName
    if (!fullName.startsWith(prefix, ignoreCase = true)) return fullName
    return fullName.removePrefix(fullName.take(prefix.length)).trimStart(' ', '/').ifBlank { fullName }
}

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
