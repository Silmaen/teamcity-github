package io.github.dlachouette.teamcity.github.retrigger

import com.intellij.openapi.diagnostic.Logger
import io.github.dlachouette.teamcity.github.api.RepoCoords
import io.github.dlachouette.teamcity.github.cache.PrInfoCache
import io.github.dlachouette.teamcity.github.feature.BridgeFeatureConfig
import io.github.dlachouette.teamcity.github.feature.BridgeFeatureReader
import io.github.dlachouette.teamcity.github.feature.BridgeGate
import io.github.dlachouette.teamcity.github.feature.BridgeProjectParams
import io.github.dlachouette.teamcity.github.feature.GateDecision
import io.github.dlachouette.teamcity.github.feature.GitHubBridgeBuildFeature
import io.github.dlachouette.teamcity.github.report.DraftCheckRunReporter
import io.github.dlachouette.teamcity.github.report.SkipReason
import jetbrains.buildServer.serverSide.BuildPromotionEx
import jetbrains.buildServer.serverSide.BuildTypeEx
import jetbrains.buildServer.serverSide.ProjectManager
import jetbrains.buildServer.serverSide.SBuild
import jetbrains.buildServer.serverSide.SBuildType
import jetbrains.buildServer.serverSide.SQueuedBuild
import jetbrains.buildServer.serverSide.SecurityContextEx

// Reacts to `pull_request` actions that should refresh builds on
// the PR's head SHA. The per-BT decision is delegated to BridgeGate,
// which is shared with the filter / cleaner sites so the gating is
// consistent across paths.
class PullRequestEventListener(
    private val projectManager: ProjectManager,
    private val prInfoCache: PrInfoCache,
    private val draftCheckRunReporter: DraftCheckRunReporter,
    private val securityContext: SecurityContextEx,
) {

    // Webhook deliveries land here without an authenticated user in
    // the thread-local security context; the listener body must run
    // as the system user so ProjectManager's collection accessors
    // return the real BT set (they filter by current user otherwise).
    fun handle(payload: PrEventPayload) {
        securityContext.runAsSystemUnchecked { handleInternal(payload) }
    }

    private fun handleInternal(payload: PrEventPayload) {
        LOG.info(
            "Handling pull_request.${payload.action.value} for ${payload.repo.slug}#${payload.prNumber} " +
                "(draft=${payload.draft}, headSha=${payload.headSha}, headRef=${payload.headRef})"
        )

        // Invalidate before any downstream listener
        // (DraftBuildQueueCleaner on `buildTypeAddedToQueue`) refetches.
        prInfoCache.invalidate(payload.repo, payload.prNumber)

        val branchName = "pull/${payload.prNumber}"
        val candidates = findCandidateBuildTypes(payload.repo)
        if (candidates.isEmpty()) {
            LOG.info("No build types found for ${payload.repo.slug} - nothing to retrigger")
            diagnoseEmptyCandidates(payload.repo)
            return
        }

        // Bucket each candidate by the gate decision.
        val targets = mutableListOf<Pair<BuildTypeEx, BridgeFeatureConfig>>()
        val branchSkips = mutableListOf<Pair<BuildTypeEx, BridgeFeatureConfig>>()
        val draftSkips = mutableListOf<Pair<BuildTypeEx, BridgeFeatureConfig>>()
        candidates.forEach { (bt, config) ->
            when (BridgeGate.decide(
                config = config,
                branchName = branchName,
                prDraft = payload.draft,
                prHeadRef = payload.headRef,
                isManualTrigger = false,
            )) {
                GateDecision.ALLOW -> targets += bt to config
                GateDecision.SUPPRESS_DRAFT -> draftSkips += bt to config
                GateDecision.SUPPRESS_BRANCH_PR -> branchSkips += bt to config
                GateDecision.SUPPRESS_HARD,
                GateDecision.SUPPRESS_BRANCH_NON_PR -> Unit // silent
            }
        }

        postSkippedCheckRuns(branchSkips, payload, SkipReason.BRANCH_FILTER)
        postSkippedCheckRuns(draftSkips, payload, SkipReason.DRAFT_PR)

        if (targets.isEmpty()) {
            LOG.info(
                "Found ${candidates.size} matching build type(s) for ${payload.repo.slug}#${payload.prNumber} " +
                    "but gate excluded all of them from enqueue " +
                    "(branch-skipped=${branchSkips.size}, draft-skipped=${draftSkips.size})"
            )
            return
        }

        LOG.info(
            "Retriggering ${targets.size} build type(s) for ${payload.repo.slug}#${payload.prNumber} " +
                "on pull_request.${payload.action.value}"
        )
        var enqueued = 0
        var skippedExisting = 0
        targets.forEach { (bt, _) ->
            val skipReason = findExistingBuildReason(bt, branchName, payload.headSha)
            if (skipReason != null) {
                LOG.info("Skipping ${bt.externalId} for ${payload.repo.slug}#${payload.prNumber}: $skipReason")
                skippedExisting++
                return@forEach
            }
            try {
                enqueue(
                    buildType = bt,
                    branchName = branchName,
                    triggerSource = "teamcity-github-bridge: pull_request.${payload.action.value} on PR #${payload.prNumber}",
                )
                enqueued++
            } catch (e: Exception) {
                LOG.warn("Failed to enqueue ${bt.externalId} for PR #${payload.prNumber}: ${e.message}")
            }
        }
        LOG.info(
            "Enqueued $enqueued, skipped $skippedExisting existing build(s) " +
                "for ${payload.repo.slug}#${payload.prNumber}"
        )
    }

    private fun postSkippedCheckRuns(
        bucket: List<Pair<BuildTypeEx, BridgeFeatureConfig>>,
        payload: PrEventPayload,
        reason: SkipReason,
    ) {
        if (bucket.isEmpty()) return
        LOG.info(
            "Posting Skipped (${reason.name}) Check Run for ${bucket.size} BT(s) on " +
                "${payload.repo.slug}#${payload.prNumber}"
        )
        bucket.forEach { (bt, config) ->
            try {
                draftCheckRunReporter.postSkippedCheckRun(
                    buildType = bt,
                    config = config,
                    prNumber = payload.prNumber,
                    headSha = payload.headSha,
                    reason = reason,
                    headRef = payload.headRef,
                )
            } catch (e: Exception) {
                LOG.warn("Failed posting Skipped (${reason.name}) Check Run for ${bt.externalId} on PR #${payload.prNumber}: ${e.message}")
            }
        }
    }

    // Returns (BuildType, parsed feature config) for every BT whose
    // feature + project params resolve to the event's repo
    // (case-insensitive). Walks the project tree manually because
    // `ProjectManager.getAllBuildTypes()` returned empty on TC
    // 2026.1 even under runAsSystem.
    internal fun findCandidateBuildTypes(repo: RepoCoords): List<Pair<BuildTypeEx, BridgeFeatureConfig>> {
        return collectAllBuildTypes()
            .filterIsInstance<BuildTypeEx>()
            .mapNotNull { bt ->
                val config = BridgeFeatureReader.read(bt) ?: return@mapNotNull null
                if (!matchesRepo(config.repo, repo)) return@mapNotNull null
                bt to config
            }
    }

    private fun collectAllBuildTypes(): List<SBuildType> {
        val root = projectManager.rootProject
        if (root != null) {
            val fromRoot = root.buildTypes
            if (fromRoot.isNotEmpty()) return fromRoot
        }
        return projectManager.projects.flatMap { it.ownBuildTypes }
    }

    private fun diagnoseEmptyCandidates(eventRepo: RepoCoords) {
        val all = projectManager.allBuildTypes
        val activeOnly = projectManager.activeBuildTypes
        val numberOfBuildTypes = projectManager.numberOfBuildTypes
        val projects = projectManager.projects
        val root = projectManager.rootProject
        val fromRoot = root?.buildTypes.orEmpty()
        val fromProjectsFlatten = projects.flatMap { it.ownBuildTypes }
        LOG.info(
            "Diagnostic for repo=${eventRepo.slug}: " +
                "numberOfBuildTypes=$numberOfBuildTypes, " +
                "allBuildTypes.size=${all.size}, " +
                "activeBuildTypes.size=${activeOnly.size}, " +
                "projects.size=${projects.size}, " +
                "rootProject=${root?.externalId ?: "(null)"}, " +
                "rootProject.buildTypes.size=${fromRoot.size}, " +
                "projects.flatMap(ownBuildTypes).size=${fromProjectsFlatten.size}"
        )
        val active = collectAllBuildTypes()
        var seenWithFeature = 0
        LOG.info("Iterating ${active.size} BT(s) for the feature scan")
        active.forEach { bt ->
            // Mirror BridgeFeatureReader: resolve through the template
            // chain so template-inherited features are counted too.
            val features = bt.resolvedSettings.getBuildFeaturesOfType(GitHubBridgeBuildFeature.FEATURE_TYPE)
            if (features.isEmpty()) return@forEach
            seenWithFeature++
            val projParams = bt.project.parameters
            val rawRepo = projParams[BridgeProjectParams.REPO]
            val rawConn = projParams[BridgeProjectParams.CONNECTION_ID]
            val config = BridgeFeatureReader.read(bt)
            when {
                config == null -> LOG.info(
                    "  ${bt.externalId} (project=${bt.project.externalId}): has feature; " +
                        "config unresolved (project.parameters[repo]='$rawRepo', [connectionId]='$rawConn')"
                )
                !matchesRepo(config.repo, eventRepo) -> LOG.info(
                    "  ${bt.externalId} (project=${bt.project.externalId}): has feature, " +
                        "config.repo=${config.repo.slug} does not match event repo=${eventRepo.slug}"
                )
                else -> LOG.info(
                    "  ${bt.externalId} (project=${bt.project.externalId}): would have matched " +
                        "but findCandidateBuildTypes already rejected it (this should not happen — file a bug)"
                )
            }
        }
        if (seenWithFeature == 0) {
            LOG.info(
                "  No BT carries the '${GitHubBridgeBuildFeature.FEATURE_TYPE}' feature. " +
                    "Either none was added, or the feature lives on a template that the BT does not inherit from."
            )
        }
    }

    internal fun findExistingBuildReason(
        buildType: BuildTypeEx,
        branchName: String,
        headSha: String,
    ): String? {
        val running = buildType.runningBuilds.firstOrNull { build ->
            buildMatchesBranchAndSha(build, branchName, headSha)
        }
        if (running != null) return "already running (build #${running.buildNumber})"

        val queued = buildType.getQueuedBuilds(null).firstOrNull { qb ->
            queuedMatchesBranchAndSha(qb, branchName, headSha)
        }
        if (queued != null) return "already queued"

        val finished = buildType.history.asSequence()
            .take(HISTORY_SCAN_DEPTH)
            .firstOrNull { build ->
                build.canceledInfo == null && buildMatchesBranchAndSha(build, branchName, headSha)
            }
        if (finished != null) return "already finished (build #${finished.buildNumber}, status=${finished.buildStatus.text})"

        return null
    }

    private fun buildMatchesBranchAndSha(build: SBuild, branchName: String, headSha: String): Boolean =
        matchesBranchAndSha(build.branch?.name, build.revisions.map { it.revision }, branchName, headSha)

    private fun queuedMatchesBranchAndSha(qb: SQueuedBuild, branchName: String, headSha: String): Boolean {
        val promo = qb.buildPromotion
        return matchesBranchAndSha(promo.branch?.name, promo.revisions.map { it.revision }, branchName, headSha)
    }

    private fun enqueue(buildType: BuildTypeEx, branchName: String, triggerSource: String) {
        val customizer = buildType.createBuildCustomizer(null)
        customizer.setDesiredBranchName(branchName)
        // Note: setBuildComment throws on TC 2026.1 when the customizer
        // was created with a null user. The `triggerSource` argument
        // to addToQueue carries the relevant info instead.
        val promotion = customizer.createPromotion() as BuildPromotionEx
        buildType.addToQueue(promotion, triggerSource)
    }

    companion object {
        private val LOG = Logger.getInstance(PullRequestEventListener::class.java.name)

        private const val HISTORY_SCAN_DEPTH: Int = 50

        fun matchesRepo(buildTypeRepo: RepoCoords, eventRepo: RepoCoords): Boolean =
            buildTypeRepo.slug.equals(eventRepo.slug, ignoreCase = true)

        fun matchesBranchAndSha(
            buildBranch: String?,
            buildRevisions: List<String>,
            targetBranch: String,
            targetSha: String,
        ): Boolean {
            if (buildBranch != targetBranch) return false
            if (buildRevisions.isEmpty()) return false
            return buildRevisions.contains(targetSha)
        }
    }
}

enum class PrAction(val value: String) {
    OPENED("opened"),
    READY_FOR_REVIEW("ready_for_review"),
    SYNCHRONIZE("synchronize");

    companion object {
        fun fromString(s: String): PrAction? = entries.firstOrNull { it.value == s }
    }
}

data class PrEventPayload(
    val action: PrAction,
    val repo: RepoCoords,
    val prNumber: Int,
    val headSha: String,
    val baseRef: String,
    val headRef: String,
    val draft: Boolean,
)
