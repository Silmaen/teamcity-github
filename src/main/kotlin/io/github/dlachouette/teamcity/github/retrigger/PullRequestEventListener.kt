package io.github.dlachouette.teamcity.github.retrigger

import com.intellij.openapi.diagnostic.Logger
import io.github.dlachouette.teamcity.github.api.GitHubClient
import io.github.dlachouette.teamcity.github.api.RepoCoords
import io.github.dlachouette.teamcity.github.api.TokenResolver
import io.github.dlachouette.teamcity.github.cache.PrInfoCache
import io.github.dlachouette.teamcity.github.config.BridgeServerSettings
import io.github.dlachouette.teamcity.github.feature.BridgeFeatureConfig
import io.github.dlachouette.teamcity.github.feature.BridgeFeatureReader
import io.github.dlachouette.teamcity.github.feature.BridgeGate
import io.github.dlachouette.teamcity.github.feature.BridgeRefs
import io.github.dlachouette.teamcity.github.feature.BridgeTrigger
import io.github.dlachouette.teamcity.github.feature.BridgeTriggerMarker
import io.github.dlachouette.teamcity.github.feature.BridgeProjectParams
import io.github.dlachouette.teamcity.github.feature.GateDecision
import io.github.dlachouette.teamcity.github.feature.GitHubBridgeBuildFeature
import io.github.dlachouette.teamcity.github.feature.PrBuildRef
import io.github.dlachouette.teamcity.github.enrich.PrBuildEnricher
import io.github.dlachouette.teamcity.github.report.DraftCheckRunReporter
import io.github.dlachouette.teamcity.github.report.SkipReason
import io.github.dlachouette.teamcity.github.report.checkRunName
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
    private val serverSettings: BridgeServerSettings,
    private val tokenResolver: TokenResolver,
    private val gitHubClient: GitHubClient,
    private val metrics: io.github.dlachouette.teamcity.github.web.BridgeMetrics,
) {

    // Webhook deliveries land here without an authenticated user in
    // the thread-local security context; the listener body must run
    // as the system user so ProjectManager's collection accessors
    // return the real BT set (they filter by current user otherwise).
    fun handle(payload: PrEventPayload) {
        if (isFork(payload.repo, payload.headRepo, "pull_request.${payload.action.value} #${payload.prNumber}")) return
        securityContext.runAsSystemUnchecked { handleInternal(payload) }
    }

    // A PR was approved: enqueue every opted-in BT that requested
    // run-on-approval (typically expensive suites deliberately gated
    // behind review). Independent of the normal ready/synchronize gate.
    fun handleReviewApproved(payload: ReviewApprovedPayload) {
        securityContext.runAsSystemUnchecked {
            if (!serverSettings.isRepoAllowed(payload.repo.slug)) return@runAsSystemUnchecked
            if (payload.draft) return@runAsSystemUnchecked
            if (isFork(payload.repo, payload.headRepo, "review approval on #${payload.prNumber}")) return@runAsSystemUnchecked
            val branchName = BridgeRefs.prRef(payload.prNumber)
            val targets = findCandidateBuildTypes(payload.repo).filter { (_, config) ->
                config.runOnApproval && config.prTriggerEnabled &&
                    config.prTriggerBranches.matches(branchName, payload.headRef)
            }
            if (targets.isEmpty()) return@runAsSystemUnchecked
            LOG.info("PR #${payload.prNumber} approved: ${targets.size} run-on-approval BT(s) for ${payload.repo.slug}")
            targets.forEach { (bt, config) ->
                enqueueIfAbsent(bt, prBuildRefFor(config, payload.prNumber, payload.headRef), payload.headSha,
                    "teamcity-github-bridge: PR #${payload.prNumber} approved (run-on-approval)",
                    trigger = BridgeTrigger.COMMAND)
            }
        }
    }

    // A comment was posted on a PR: enqueue every opted-in BT whose
    // configured trigger phrase appears in the comment body, provided the
    // comment author is sufficiently trusted (author association on the
    // server allowlist — collaborators by default, so an arbitrary
    // outside commenter cannot start builds).
    fun handleCommentCommand(payload: CommentCommandPayload) {
        securityContext.runAsSystemUnchecked {
            if (!serverSettings.isRepoAllowed(payload.repo.slug)) return@runAsSystemUnchecked
            if (!serverSettings.isCommentAuthorAllowed(payload.authorAssociation)) {
                LOG.info(
                    "Ignoring PR #${payload.prNumber} comment command from ${payload.commenter} " +
                        "(association=${payload.authorAssociation} not allowed) on ${payload.repo.slug}"
                )
                return@runAsSystemUnchecked
            }
            val targets = findCandidateBuildTypes(payload.repo).filter { (_, config) ->
                config.prTriggerEnabled && config.commentTrigger.isNotBlank() &&
                    payload.body.contains(config.commentTrigger, ignoreCase = true)
            }
            if (targets.isEmpty()) return@runAsSystemUnchecked
            LOG.info("PR #${payload.prNumber} comment by ${payload.commenter} matched ${targets.size} BT(s) on ${payload.repo.slug}")
            val pr = resolvePrInfo(targets.first(), payload.prNumber)
            if (pr != null && isFork(payload.repo, pr.headRepo, "comment command on #${payload.prNumber}")) {
                return@runAsSystemUnchecked
            }
            targets.forEach { (bt, config) ->
                enqueueIfAbsent(bt, prBuildRefFor(config, payload.prNumber, pr?.headRef), pr?.headSha.orEmpty(),
                    "teamcity-github-bridge: comment command '${config.commentTrigger}' by ${payload.commenter}",
                    trigger = BridgeTrigger.COMMAND)
            }
        }
    }

    // Entry point for the external API: enqueue a build of `externalId`
    // on `branch` (e.g. "pull/12" or "main"). Runs as the system user.
    fun triggerBuild(externalId: String, branch: String): TriggerResult {
        var result = TriggerResult(false, "not executed")
        securityContext.runAsSystemUnchecked {
            val bt = projectManager.findBuildTypeByExternalId(externalId) as? BuildTypeEx
            if (bt == null) {
                result = TriggerResult(false, "unknown build type '$externalId'")
                return@runAsSystemUnchecked
            }
            if (serverSettings.dryRun()) {
                result = TriggerResult(true, "dry-run: would enqueue $externalId on $branch")
                return@runAsSystemUnchecked
            }
            result = try {
                enqueue(bt, branch, "teamcity-github-bridge: external API trigger", BridgeTrigger.COMMAND)
                metrics.inc(io.github.dlachouette.teamcity.github.web.BridgeMetrics.BUILDS_ENQUEUED)
                TriggerResult(true, "queued $externalId on $branch")
            } catch (e: Exception) {
                LOG.warn("External API trigger failed for $externalId on $branch: ${e.message}")
                TriggerResult(false, "enqueue failed: ${e.message}")
            }
        }
        return result
    }

    private fun resolvePrInfo(
        target: Pair<BuildTypeEx, BridgeFeatureConfig>,
        prNumber: Int,
    ): io.github.dlachouette.teamcity.github.api.PrInfo? {
        val access = tokenResolver.resolveAccessToken(target.first.project, target.second.connectionId, target.second.repo)
            ?: return null
        return prInfoCache.get(target.second.repo, prNumber, access.token, access.apiBase)
    }

    // The re-run button was clicked on a TeamCity Check Run: map it back
    // to its BuildType by the Check Run name and enqueue a fresh build,
    // even if a finished build already exists for that head (that's the point).
    fun handleRerun(payload: RerunRequestPayload) {
        securityContext.runAsSystemUnchecked {
            if (!serverSettings.isRepoAllowed(payload.repo.slug)) return@runAsSystemUnchecked
            val branchName = when {
                payload.prNumber != null -> BridgeRefs.prRef(payload.prNumber)
                !payload.headBranch.isNullOrBlank() -> payload.headBranch
                else -> {
                    LOG.info("check_run rerequested for ${payload.repo.slug} but no PR number/head branch; ignoring")
                    return@runAsSystemUnchecked
                }
            }
            val match = findCandidateBuildTypes(payload.repo).firstOrNull { (bt, _) ->
                checkRunName(bt) == payload.checkRunName
            }
            if (match == null) {
                LOG.info("check_run rerequested '${payload.checkRunName}' matched no BuildType for ${payload.repo.slug}")
                return@runAsSystemUnchecked
            }
            // Re-run skips only currently running/queued builds, NOT finished
            // ones (re-running a finished build is exactly the intent).
            val ref = payload.prNumber
                ?.let { prBuildRefFor(match.second, it, payload.headBranch) }
                ?: branchName
            enqueueIfAbsent(match.first, ref, payload.headSha,
                "teamcity-github-bridge: re-run requested from GitHub",
                trigger = BridgeTrigger.COMMAND, ignoreFinished = true)
        }
    }

    // "Re-run all checks" was clicked in the GitHub Checks UI: re-run every
    // opted-in build configuration for that head. With the
    // `rerunAll.onlyFailed` setting on, only those whose last build at that
    // commit failed — a build configuration that never ran at this commit has
    // no failure to re-run, so it is left alone.
    fun handleRerunAll(payload: RerunAllPayload) {
        securityContext.runAsSystemUnchecked {
            if (!serverSettings.isRepoAllowed(payload.repo.slug)) return@runAsSystemUnchecked
            val onlyFailed = serverSettings.rerunAllOnlyFailed()

            val targets = findCandidateBuildTypes(payload.repo).mapNotNull { (bt, config) ->
                val ref = refForRerun(config, payload) ?: return@mapNotNull null
                if (onlyFailed && !lastBuildFailedAt(bt, ref, payload.headSha)) return@mapNotNull null
                Triple(bt, ref, config)
            }
            if (targets.isEmpty()) {
                LOG.info("check_suite rerequested for ${payload.repo.slug}@${payload.headSha}: " +
                    "no matching build configuration${if (onlyFailed) " with a failed build" else ""}")
                return@runAsSystemUnchecked
            }
            LOG.info("check_suite rerequested for ${payload.repo.slug}@${payload.headSha}: " +
                "re-running ${targets.size} build configuration(s)${if (onlyFailed) " (failed only)" else ""}")
            targets.forEach { (bt, ref, _) ->
                enqueueIfAbsent(bt, ref, payload.headSha,
                    "teamcity-github-bridge: re-run all requested from GitHub",
                    trigger = BridgeTrigger.COMMAND, ignoreFinished = true)
            }
        }
    }

    // The suite gives a head branch and, for a PR, its number; either is
    // enough to name the ref this build configuration would use.
    private fun refForRerun(config: BridgeFeatureConfig, payload: RerunAllPayload): String? = when {
        payload.prNumber != null -> prBuildRefFor(config, payload.prNumber, payload.headBranch)
        !payload.headBranch.isNullOrBlank() -> payload.headBranch
        else -> null
    }

    // True when the most recent finished build of `bt` at (ref, sha) failed.
    // Cancelled builds are ignored: they carry no verdict.
    private fun lastBuildFailedAt(bt: BuildTypeEx, branchName: String, headSha: String): Boolean {
        val build = bt.history.asSequence()
            .take(HISTORY_SCAN_DEPTH)
            .firstOrNull { it.canceledInfo == null && buildMatchesBranchAndSha(it, branchName, headSha) }
            ?: return false
        return build.buildStatus.isFailed
    }

    // Shared enqueue helper: skips when a matching build is already
    // running/queued (and, unless ignoreFinished, already finished),
    // honours dry-run, and counts the enqueue.
    private fun enqueueIfAbsent(
        bt: BuildTypeEx,
        branchName: String,
        headSha: String,
        triggerSource: String,
        trigger: BridgeTrigger = BridgeTrigger.AUTO,
        ignoreFinished: Boolean = false,
    ) {
        val running = bt.runningBuilds.any { buildMatchesBranchAndSha(it, branchName, headSha) }
        val queued = bt.getQueuedBuilds(null).any { queuedMatchesBranchAndSha(it, branchName, headSha) }
        if (running || queued) {
            LOG.info("Skipping ${bt.externalId} on $branchName: already ${if (running) "running" else "queued"}")
            return
        }
        if (!ignoreFinished && findExistingBuildReason(bt, branchName, headSha) != null) return
        if (serverSettings.dryRun()) {
            LOG.info("[dry-run] would enqueue ${bt.externalId} on $branchName ($triggerSource)")
            return
        }
        try {
            enqueue(bt, branchName, triggerSource, trigger)
            metrics.inc(io.github.dlachouette.teamcity.github.web.BridgeMetrics.BUILDS_ENQUEUED)
        } catch (e: Exception) {
            LOG.warn("Failed to enqueue ${bt.externalId} on $branchName: ${e.message}")
        }
    }

    private fun handleInternal(payload: PrEventPayload) {
        LOG.info(
            "Handling pull_request.${payload.action.value} for ${payload.repo.slug}#${payload.prNumber} " +
                "(draft=${payload.draft}, headSha=${payload.headSha}, headRef=${payload.headRef})"
        )

        // Server-level allowlist: when set, ignore repos not on it.
        if (!serverSettings.isRepoAllowed(payload.repo.slug)) {
            LOG.info("Repo ${payload.repo.slug} is not on the allowlist; ignoring pull_request.${payload.action.value}")
            return
        }

        // Invalidate before any downstream listener
        // (DraftBuildQueueCleaner on `buildTypeAddedToQueue`) refetches.
        prInfoCache.invalidate(payload.repo, payload.prNumber)

        // Builds that already ran on this head before the PR existed carry no
        // PR link yet (G12b) — give them one now.
        retroAssociate(payload)

        // A closed/merged PR has nothing to retrigger: cancel any builds
        // still queued for its head, then stop. Running builds are left to
        // finish (stopping them needs an acting user / extra permissions).
        if (payload.action == PrAction.CLOSED) {
            cancelQueuedForClosedPr(payload)
            return
        }

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
        val metadataSkips = mutableListOf<Pair<BuildTypeEx, BridgeFeatureConfig>>()
        candidates.forEach { (bt, config) ->
            when (BridgeGate.decide(
                config = config,
                branchName = prBuildRefFor(config, payload.prNumber, payload.headRef),
                prNumber = payload.prNumber,
                prDraft = payload.draft,
                prHeadRef = payload.headRef,
                trigger = BridgeTrigger.AUTO,
                prTitle = payload.title,
                prBody = payload.body,
                prLabels = payload.labels,
            )) {
                GateDecision.ALLOW -> targets += bt to config
                GateDecision.SUPPRESS_DRAFT -> draftSkips += bt to config
                GateDecision.SUPPRESS_BRANCH_PR -> branchSkips += bt to config
                GateDecision.SUPPRESS_METADATA -> metadataSkips += bt to config
                GateDecision.SUPPRESS_HARD,
                GateDecision.SUPPRESS_BRANCH_NON_PR -> Unit // silent
            }
        }

        if (payload.action.reportsSkips) {
            postSkippedCheckRuns(branchSkips, payload, SkipReason.BRANCH_FILTER)
            postSkippedCheckRuns(draftSkips, payload, SkipReason.DRAFT_PR)
            postSkippedCheckRuns(metadataSkips, payload, SkipReason.METADATA_FILTER)
        }

        // Monorepo path filtering: drop targets whose path filter matches
        // none of the PR's changed files (posts a "paths out of scope" skip).
        val finalTargets = applyPathFilter(targets, payload)

        if (finalTargets.isEmpty()) {
            LOG.info(
                "Found ${candidates.size} matching build type(s) for ${payload.repo.slug}#${payload.prNumber} " +
                    "but gate/path-filter excluded all of them from enqueue " +
                    "(branch-skipped=${branchSkips.size}, draft-skipped=${draftSkips.size})"
            )
            return
        }

        LOG.info(
            "Retriggering ${finalTargets.size} build type(s) for ${payload.repo.slug}#${payload.prNumber} " +
                "on pull_request.${payload.action.value}"
        )
        var enqueued = 0
        var skippedExisting = 0
        finalTargets.forEach { (bt, config) ->
            val branchName = prBuildRefFor(config, payload.prNumber, payload.headRef)
            val skipReason = findExistingBuildReason(bt, branchName, payload.headSha)
            if (skipReason != null) {
                LOG.info("Skipping ${bt.externalId} for ${payload.repo.slug}#${payload.prNumber}: $skipReason")
                skippedExisting++
                return@forEach
            }
            if (serverSettings.dryRun()) {
                LOG.info("[dry-run] would enqueue ${bt.externalId} for ${payload.repo.slug}#${payload.prNumber} (no build added)")
                return@forEach
            }
            try {
                enqueue(
                    buildType = bt,
                    branchName = branchName,
                    triggerSource = "teamcity-github-bridge: pull_request.${payload.action.value} on PR #${payload.prNumber}",
                )
                metrics.inc(io.github.dlachouette.teamcity.github.web.BridgeMetrics.BUILDS_ENQUEUED)
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

    // Keep only targets whose path filter (if any) matches at least one
    // file changed by the PR. Targets without a path filter are always
    // kept. The changed-files list is fetched once, lazily, and only when
    // some target actually uses a filter. On any resolution failure we
    // fail OPEN (keep all targets) so path filtering never silently
    // swallows builds.
    private fun applyPathFilter(
        targets: List<Pair<BuildTypeEx, BridgeFeatureConfig>>,
        payload: PrEventPayload,
    ): List<Pair<BuildTypeEx, BridgeFeatureConfig>> {
        val sample = targets.firstOrNull { !it.second.pathFilter.isEmpty() } ?: return targets

        val access = tokenResolver.resolveAccessToken(sample.first.project, sample.second.connectionId, sample.second.repo)
        if (access == null) {
            LOG.warn("Path filter: token resolution failed for ${payload.repo.slug}; keeping all targets")
            return targets
        }
        val changed = gitHubClient.listPrFiles(access.token, payload.repo, payload.prNumber, access.apiBase)
        if (changed.isEmpty()) {
            LOG.warn("Path filter: no changed files resolved for ${payload.repo.slug}#${payload.prNumber}; keeping all targets")
            return targets
        }

        val kept = mutableListOf<Pair<BuildTypeEx, BridgeFeatureConfig>>()
        val dropped = mutableListOf<Pair<BuildTypeEx, BridgeFeatureConfig>>()
        targets.forEach { entry ->
            val filter = entry.second.pathFilter
            if (filter.isEmpty() || changed.any { filter.matches(it) }) kept += entry else dropped += entry
        }
        if (payload.action.reportsSkips) postSkippedCheckRuns(dropped, payload, SkipReason.PATH_FILTER)
        if (dropped.isNotEmpty()) {
            LOG.info("Path filter excluded ${dropped.size} BT(s) for ${payload.repo.slug}#${payload.prNumber} (${changed.size} files changed)")
        }
        return kept
    }

    // G12b: a build launched on `Feature/x` before the PR was opened has no
    // PR tag, so it is invisible to a search by PR number. Tag the builds
    // that already exist at this head — no API call needed, the payload
    // carries the number and the draft state.
    //
    // Bounded by the history scan depth and skipped entirely for a closed
    // PR (there is nothing left to associate).
    private fun retroAssociate(payload: PrEventPayload) {
        if (payload.action == PrAction.CLOSED || payload.headSha.isBlank()) return
        val stateTag = if (payload.draft) PrBuildEnricher.TAG_DRAFT else PrBuildEnricher.TAG_READY
        val wanted = listOfNotNull(PrBuildEnricher.prTagFor(serverSettings, payload.prNumber), stateTag)
        var tagged = 0
        findCandidateBuildTypes(payload.repo).forEach { (bt, config) ->
            val ref = prBuildRefFor(config, payload.prNumber, payload.headRef)
            bt.history.asSequence()
                .take(HISTORY_SCAN_DEPTH)
                .filter { buildMatchesBranchAndSha(it, ref, payload.headSha) }
                .forEach eachBuild@{ build ->
                    val missing = wanted.filterNot { build.tags.contains(it) }
                    if (missing.isEmpty()) return@eachBuild
                    try {
                        build.setTags(build.tags + missing)
                        tagged++
                    } catch (e: Exception) {
                        LOG.warn("Failed tagging build #${build.buildNumber} of ${bt.externalId} " +
                            "for ${payload.repo.slug}#${payload.prNumber}: ${e.message}")
                    }
                }
        }
        if (tagged > 0) {
            LOG.info("Associated $tagged existing build(s) with ${payload.repo.slug}#${payload.prNumber}")
        }
    }

    // Remove every build still queued for the closed/merged PR's head
    // across all candidate BTs, so closing a PR mid-build stops burning
    // agent minutes. Honours dry-run.
    private fun cancelQueuedForClosedPr(payload: PrEventPayload) {
        if (!serverSettings.queueCleanupEnabled()) {
            LOG.info("Queue cleanup is disabled server-wide; leaving builds queued for ${payload.repo.slug}#${payload.prNumber}")
            return
        }
        val verb = if (payload.merged) "merged" else "closed"
        val candidates = findCandidateBuildTypes(payload.repo)
        var removed = 0
        candidates.forEach { (bt, config) ->
            val branchName = prBuildRefFor(config, payload.prNumber, payload.headRef)
            bt.getQueuedBuilds(null)
                .filter { it.buildPromotion.branch?.name == branchName }
                .forEach eachQueued@{ qb ->
                    if (serverSettings.dryRun()) {
                        LOG.info("[dry-run] would remove queued ${bt.externalId} for ${payload.repo.slug}#${payload.prNumber}")
                        return@eachQueued
                    }
                    try {
                        qb.removeFromQueue(null, "teamcity-github-bridge: PR #${payload.prNumber} $verb")
                        metrics.inc(io.github.dlachouette.teamcity.github.web.BridgeMetrics.BUILDS_CANCELLED)
                        removed++
                    } catch (e: Exception) {
                        LOG.warn("Failed removing queued ${bt.externalId} for PR #${payload.prNumber}: ${e.message}")
                    }
                }
        }
        LOG.info("PR #${payload.prNumber} $verb: removed $removed queued build(s) for ${payload.repo.slug}")
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

    // `rootProject.buildTypes` is the whole tree and is what works on TC
    // 2026.1; the flattened walk is the fallback for a server whose root
    // reports none (seen while diagnosing the empty-candidate case).
    private fun collectAllBuildTypes(): List<SBuildType> =
        projectManager.rootProject.buildTypes.ifEmpty {
            projectManager.projects.flatMap { it.ownBuildTypes }
        }

    private fun diagnoseEmptyCandidates(eventRepo: RepoCoords) {
        val all = projectManager.allBuildTypes
        val activeOnly = projectManager.activeBuildTypes
        val numberOfBuildTypes = projectManager.numberOfBuildTypes
        val projects = projectManager.projects
        val root = projectManager.rootProject
        val fromRoot = root.buildTypes
        val fromProjectsFlatten = projects.flatMap { it.ownBuildTypes }
        LOG.info(
            "Diagnostic for repo=${eventRepo.slug}: " +
                "numberOfBuildTypes=$numberOfBuildTypes, " +
                "allBuildTypes.size=${all.size}, " +
                "activeBuildTypes.size=${activeOnly.size}, " +
                "projects.size=${projects.size}, " +
                "rootProject=${root.externalId}, " +
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

    // R9 / G19: the bridge is attached to one repository, never to its
    // forks. A PR whose head branch lives elsewhere is out of scope — and in
    // branch-source mode it is not even buildable, since that ref does not
    // exist here.
    //
    // Fail-open on a blank head repo: GitHub omits it when the fork has been
    // deleted, and refusing to act on a missing field would be a silent
    // regression for anyone whose payloads lack it.
    private fun isFork(repo: RepoCoords, headRepo: String, what: String): Boolean {
        if (headRepo.isBlank() || headRepo.equals(repo.slug, ignoreCase = true)) return false
        LOG.info("Ignoring $what on ${repo.slug}: head lives in fork '$headRepo' (forks are out of scope)")
        metrics.inc(io.github.dlachouette.teamcity.github.web.BridgeMetrics.FORK_EVENTS_IGNORED)
        return true
    }

    private fun enqueue(
        buildType: BuildTypeEx,
        branchName: String,
        triggerSource: String,
        trigger: BridgeTrigger = BridgeTrigger.AUTO,
    ) {
        val customizer = buildType.createBuildCustomizer(null)
        customizer.setDesiredBranchName(branchName)
        // Stamp explicit commands so the queue-time gate sites treat them
        // like a manual Run and do not undo them (see BridgeTriggerMarker).
        if (trigger != BridgeTrigger.AUTO) {
            customizer.setParameters(BridgeTriggerMarker.parametersFor(trigger))
        }
        // Note: setBuildComment throws on TC 2026.1 when the customizer
        // was created with a null user. The `triggerSource` argument
        // to addToQueue carries the relevant info instead.
        val promotion = customizer.createPromotion() as BuildPromotionEx
        buildType.addToQueue(promotion, triggerSource)
    }

    companion object {
        private val LOG = Logger.getInstance(PullRequestEventListener::class.java.name)

        private const val HISTORY_SCAN_DEPTH: Int = 50

        // The ref a PR build of `config` runs on: the synthetic `pull/N`, or
        // — in branch-source mode — the PR's own head branch. Falls back to
        // `pull/N` when the head ref is unknown (a payload without it, or a
        // fork, which the fork guard rejects earlier anyway).
        fun prBuildRefFor(config: BridgeFeatureConfig, prNumber: Int, headRef: String?): String =
            if (config.prBuildRef == PrBuildRef.BRANCH && !headRef.isNullOrBlank()) headRef
            else BridgeRefs.prRef(prNumber)

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

// The `pull_request` actions the bridge acts on.
//
// `reportsSkips` says whether an excluded build configuration should get a
// "Skipped: …" Check Run for this action. It is false for the actions that
// merely *re-evaluate* an unchanged commit — a label added, a label removed, a
// title edited: a Check Run row is keyed on (name, commit), so posting a
// "Skipped" row there would overwrite the real result an earlier build already
// published for that commit. Turning a green row into "Skipped" because
// someone removed a label is not a reasonable thing to do.
enum class PrAction(val value: String, val reportsSkips: Boolean = true) {
    OPENED("opened"),
    REOPENED("reopened"),
    READY_FOR_REVIEW("ready_for_review"),
    SYNCHRONIZE("synchronize"),
    // Re-evaluations of the same commit: enqueue what became eligible, and
    // say nothing about what did not.
    LABELED("labeled", reportsSkips = false),
    UNLABELED("unlabeled", reportsSkips = false),
    EDITED("edited", reportsSkips = false),
    CLOSED("closed");

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
    // true only on `closed` events where the PR was merged (vs. closed
    // without merging). Informational; both cancel in-flight builds.
    val merged: Boolean = false,
    // PR metadata for the title/body/label gate. Read straight from the
    // webhook payload (no extra API call).
    val title: String = "",
    val body: String = "",
    val labels: List<String> = emptyList(),
    // `owner/name` the head branch lives in — see ForkGuard.
    val headRepo: String = "",
)

// pull_request_review submitted with state=approved.
data class ReviewApprovedPayload(
    val repo: RepoCoords,
    val prNumber: Int,
    val headSha: String,
    val headRef: String,
    val draft: Boolean,
    val headRepo: String = "",
)

// A "Re-run all checks" click in the GitHub Checks UI
// (`check_suite.rerequested`). Unlike `check_run.rerequested` it names no
// build configuration: every opted-in one for that head is a candidate.
data class RerunAllPayload(
    val repo: RepoCoords,
    val headSha: String,
    val headBranch: String?,
    val prNumber: Int?,
)

// Result of an external-API build trigger.
data class TriggerResult(val queued: Boolean, val detail: String)

// issue_comment created on a PR (a potential build command).
data class CommentCommandPayload(
    val repo: RepoCoords,
    val prNumber: Int,
    val body: String,
    val authorAssociation: String,
    val commenter: String,
)

// check_run `rerequested` (the re-run button in the GitHub Checks UI).
data class RerunRequestPayload(
    val repo: RepoCoords,
    val checkRunName: String,
    val headSha: String,
    val prNumber: Int?,
    val headBranch: String?,
)
