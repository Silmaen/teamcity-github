package io.github.dlachouette.teamcity.github.report

import com.intellij.openapi.diagnostic.Logger
import io.github.dlachouette.teamcity.github.api.CheckRunConclusion
import io.github.dlachouette.teamcity.github.api.CheckRunRequest
import io.github.dlachouette.teamcity.github.api.CheckRunStatus
import io.github.dlachouette.teamcity.github.api.GitHubClient
import io.github.dlachouette.teamcity.github.api.RepoCoords
import io.github.dlachouette.teamcity.github.api.TokenResolver
import io.github.dlachouette.teamcity.github.cache.PrInfoCache
import io.github.dlachouette.teamcity.github.feature.BridgeFeatureConfig
import io.github.dlachouette.teamcity.github.feature.BridgeFeatureReader
import io.github.dlachouette.teamcity.github.feature.BridgeGate
import io.github.dlachouette.teamcity.github.feature.GateDecision
import io.github.dlachouette.teamcity.github.queue.QueueCleanupPolicy
import io.github.dlachouette.teamcity.github.feature.resolvesPrFromCommit
import jetbrains.buildServer.messages.Status
import jetbrains.buildServer.serverSide.BuildPromotion
import jetbrains.buildServer.serverSide.BuildRevision
import jetbrains.buildServer.serverSide.BuildServerAdapter
import jetbrains.buildServer.serverSide.SBuild
import jetbrains.buildServer.serverSide.SBuildServer
import jetbrains.buildServer.serverSide.SBuildType
import jetbrains.buildServer.serverSide.SQueuedBuild
import jetbrains.buildServer.serverSide.SRunningBuild
import jetbrains.buildServer.serverSide.artifacts.BuildArtifactsViewMode
import jetbrains.buildServer.serverSide.WebLinks
import jetbrains.buildServer.serverSide.executors.ExecutorServices
import jetbrains.buildServer.users.User
import java.util.concurrent.TimeUnit

// Publishes a Check Run to GitHub at every lifecycle transition for
// an opted-in PR build:
//   - buildTypeAddedToQueue   -> status=queued, "Queued"
//   - buildStarted            -> status=in_progress, "Building"
//   - buildInterrupted        -> status=completed, conclusion=cancelled
//   - buildFinished           -> status=completed, conclusion derived
//   - buildRemovedFromQueue   -> status=completed, conclusion=cancelled
//
// GitHub dedups Check Runs by (name, head_sha), so every event
// transitions the same row: queued -> in_progress -> completed.
//
// The "queued" publish is retried on a scheduler because the VCS
// revision (needed for head_sha) is resolved by a background task and
// is frequently not ready yet when buildTypeAddedToQueue fires.
class BuildStatusCheckRunPublisher(
    buildServer: SBuildServer,
    private val tokenResolver: TokenResolver,
    private val gitHubClient: GitHubClient,
    private val webLinks: WebLinks,
    private val prInfoCache: PrInfoCache,
    private val gateContextResolver: io.github.dlachouette.teamcity.github.feature.GateContextResolver,
    private val serverSettings: io.github.dlachouette.teamcity.github.config.BridgeServerSettings,
    private val metrics: io.github.dlachouette.teamcity.github.web.BridgeMetrics,
    private val prSummaryCommenter: PrSummaryCommenter,
    private val executorServices: ExecutorServices,
) : BuildServerAdapter() {

    init {
        buildServer.addListener(this)
    }

    override fun buildTypeAddedToQueue(queuedBuild: SQueuedBuild) {
        try {
            attemptPublishQueued(queuedBuild, attempt = 1)
        } catch (e: Exception) {
            LOG.warn("Failed publishing queued Check Run for ${queuedBuild.buildType.externalId}: ${e.message}", e)
        }
    }

    override fun buildStarted(build: SRunningBuild) {
        try {
            publishInProgress(build)
        } catch (e: Exception) {
            LOG.warn("Failed publishing in-progress Check Run for ${build.buildTypeExternalId} #${build.buildId}: ${e.message}", e)
        }
    }

    override fun buildFinished(build: SRunningBuild) {
        try {
            publishCompleted(build)
        } catch (e: Exception) {
            LOG.warn("Failed publishing completed Check Run for ${build.buildTypeExternalId} #${build.buildId}: ${e.message}", e)
        }
    }

    override fun buildInterrupted(build: SRunningBuild) {
        try {
            publishCompleted(build)
        } catch (e: Exception) {
            LOG.warn("Failed publishing cancelled Check Run for interrupted ${build.buildTypeExternalId} #${build.buildId}: ${e.message}", e)
        }
    }

    // `buildRemovedFromQueue` fires for EVERY exit from the queue —
    // the SDK documents it as "started, deleted, optimized, etc.", so
    // a null user does NOT mean "cancelled", and a non-null associated
    // build does NOT always mean the build is running (a build that
    // "failed to start" on a failed snapshot dependency also leaves a
    // finished record). `publishQueueRemoved` sorts the cases out so the
    // "Queued" row always reaches a terminal state.
    //
    // The previous `if (user == null) return` guard was meant to skip
    // the build-started case, but it also swallowed system removals
    // such as a failed snapshot dependency — leaving their "Queued"
    // Check Run stuck forever on GitHub.
    override fun buildRemovedFromQueue(queuedBuild: SQueuedBuild, user: User?, comment: String) {
        try {
            publishQueueRemoved(queuedBuild, user, comment)
        } catch (e: Exception) {
            LOG.warn("Failed publishing Check Run for queue removal of ${queuedBuild.buildType.externalId}: ${e.message}", e)
        }
    }

    // Publishes the "Queued" Check Run. At the instant buildTypeAddedToQueue
    // fires, TeamCity has often not finished collecting the VCS revisions for
    // the freshly-enqueued build (especially for builds we enqueue from a
    // webhook), so promotion.revisions is still empty and resolveContext
    // cannot resolve the head SHA. Giving up silently there left PR builds
    // with NO "Queued" row until they actually started — the reported bug.
    // Instead we retry a few times on TeamCity's shared scheduler until the
    // revision is resolved.
    //
    // GitHub dedups Check Runs by (name, head_sha), so a late "Queued" post
    // would clobber the "in_progress"/"completed" row a started build already
    // owns. Every attempt therefore aborts if the build has meanwhile left
    // the queue.
    private fun attemptPublishQueued(queuedBuild: SQueuedBuild, attempt: Int) {
        val promotion = queuedBuild.buildPromotion

        // The build already started/finished — its own lifecycle events own
        // the row — or it left the queue with no running build (a removal
        // buildRemovedFromQueue handles). Either way, do not post a stale
        // "Queued" that would overwrite a more advanced state.
        if (promotion.associatedBuild != null) return
        if (promotion.queuedBuild == null) return

        val buildType = promotion.buildType ?: return
        // Not one of our builds (no bridge feature, or repo not allowed):
        // never retry — the revision will never make it "ours".
        val config = BridgeFeatureReader.read(buildType) ?: return
        if (!serverSettings.isRepoAllowed(config.repo.slug)) return

        val revisionReady = promotion.revisions.firstOrNull()?.revision?.isNotBlank() == true
        when (decideQueuedAction(revisionReady, attempt)) {
            QueuedAction.GIVE_UP -> {
                LOG.debug("Giving up queued Check Run for ${buildType.externalId} (${promotion.branch?.name}): " +
                    "no VCS revision resolved after $attempt attempts")
                return
            }
            QueuedAction.RETRY -> {
                LOG.debug("Deferring queued Check Run for ${buildType.externalId}; revisions not resolved yet " +
                    "(attempt $attempt/$MAX_QUEUED_ATTEMPTS)")
                executorServices.normalExecutorService.schedule(
                    Runnable {
                        try {
                            attemptPublishQueued(queuedBuild, attempt + 1)
                        } catch (e: Exception) {
                            LOG.warn("Deferred queued Check Run failed for ${buildType.externalId}: ${e.message}", e)
                        }
                    },
                    QUEUED_RETRY_DELAY_MS, TimeUnit.MILLISECONDS,
                )
                return
            }
            QueuedAction.PUBLISH -> Unit // fall through to publish below
        }

        val ctx = resolveContext(buildType, promotion.revisions) ?: return
        if (willBeRemovedFromQueue(queuedBuild, promotion, ctx)) {
            LOG.debug("Skipping queued Check Run for ${ctx.buildType.externalId}; the cleaner will own this row")
            return
        }
        val request = CheckRunRequest(
            name = checkRunName(ctx.buildType),
            headSha = ctx.headSha,
            status = CheckRunStatus.QUEUED,
            conclusion = null,
            outputTitle = "Queued",
            outputSummary = "TeamCity has queued this build; waiting for a compatible agent.",
            detailsUrl = safeUrl { webLinks.getQueuedBuildUrl(queuedBuild) },
        )
        post(ctx, request, "queued")
    }

    // True iff `DraftBuildQueueCleaner` is about to remove this queued build,
    // in which case the "Queued" row would race with the terminal row the
    // cleaner posts (Skipped, or the republished success). This asks the same
    // question the cleaner does — it is not a publication policy, which
    // depends on `publishChecks` alone.
    private fun willBeRemovedFromQueue(
        queuedBuild: SQueuedBuild,
        promotion: BuildPromotion,
        ctx: PrBuildContext,
    ): Boolean {
        val gate = gateContextResolver.resolve(promotion, ctx.config, queuedBuild.triggeredBy.isTriggeredByUser)
            ?: return false
        val decision = BridgeGate.decide(
            ctx.config, gate.branchName, gate.prNumber, gate.pr?.draft, gate.pr?.headRef, gate.trigger,
            gate.pr?.title.orEmpty(), gate.pr?.body.orEmpty(), gate.pr?.labels.orEmpty(),
        )
        return QueueCleanupPolicy.removes(decision, gate.trigger, serverSettings.queueCleanupEnabled())
    }

    private fun publishInProgress(build: SBuild) {
        val ctx = resolveContext(build.buildType, build.revisions) ?: return
        val request = CheckRunRequest(
            name = checkRunName(ctx.buildType),
            headSha = ctx.headSha,
            status = CheckRunStatus.IN_PROGRESS,
            conclusion = null,
            outputTitle = "Building",
            outputSummary = "TeamCity build #${build.buildNumber} is running.",
            detailsUrl = safeUrl { webLinks.getViewResultsUrl(build) },
        )
        post(ctx, request, "in-progress")
    }

    private fun publishCompleted(build: SRunningBuild) {
        // A build that "failed to start" (e.g. a failed snapshot
        // dependency) may carry no revisions of its own; fall back to the
        // promotion's revisions (resolved at enqueue) so we still resolve
        // the head SHA and report it instead of leaving the row stuck.
        val revisions = build.revisions.ifEmpty { build.buildPromotion.revisions }
        val ctx = resolveContext(build.buildType, revisions) ?: return
        val mapping = mapBuildOutcome(build.buildStatus, build.isInterrupted)
        val summary = build.statusDescriptor.text.orEmpty()
            .takeIf { it.isNotBlank() }
            ?: "TeamCity build finished with status ${build.buildStatus}"

        val request = CheckRunRequest(
            name = checkRunName(ctx.buildType),
            headSha = ctx.headSha,
            status = CheckRunStatus.COMPLETED,
            conclusion = mapping.conclusion,
            outputTitle = mapping.title,
            outputSummary = truncateSummary(summary),
            detailsUrl = safeUrl { webLinks.getViewResultsUrl(build) },
            outputText = joinSections(failureDetails(build), artifactSection(build)),
        )
        post(ctx, request, "completed (${mapping.conclusion.apiValue})")
        maybeUpdatePrComment(build, ctx, mapping)
    }

    // Optional sticky PR summary comment (off by default). Only for builds
    // that belong to a PR; skipped in dry-run. Reuses the build's
    // installation token, which must carry the App's pull-requests write
    // permission.
    private fun maybeUpdatePrComment(build: SBuild, ctx: PrBuildContext, mapping: BuildOutcomeMapping) {
        if (!serverSettings.prCommentEnabled() || serverSettings.dryRun()) return
        val prNumber = resolvePrNumber(build, ctx) ?: return
        val emoji = when (mapping.conclusion) {
            CheckRunConclusion.SUCCESS -> "✅"
            CheckRunConclusion.FAILURE, CheckRunConclusion.TIMED_OUT -> "❌"
            CheckRunConclusion.CANCELLED -> "⚪"
            else -> "⚠️"
        }
        prSummaryCommenter.upsert(
            accessToken = ctx.accessToken,
            repo = ctx.repo,
            prNumber = prNumber,
            apiBase = ctx.apiBase,
            checkName = checkRunName(ctx.buildType),
            row = PrSummaryCommenter.Row(
                emoji = emoji,
                text = mapping.title,
                url = safeUrl { webLinks.getViewResultsUrl(build) },
                artifactsUrl = artifactsUrl(build),
            ),
        )
    }

    // The PR this build reports into, if any: straight from the `pull/N`
    // ref, or — for a build launched on a plain branch ref — the open PR
    // whose head is the built commit. The Check Run itself needs none of
    // this (GitHub attaches it to the commit, and every PR whose head is
    // that commit shows it); only the comment needs an issue number.
    private fun resolvePrNumber(build: SBuild, ctx: PrBuildContext): Int? {
        val branch = build.branch?.name ?: return null
        if (branch.startsWith("pull/")) return branch.removePrefix("pull/").toIntOrNull()
        if (!ctx.config.resolvesPrFromCommit(serverSettings.branchPrLookupEnabled())) return null
        return prInfoCache.getForCommit(ctx.repo, ctx.headSha, ctx.accessToken, ctx.apiBase)?.number
    }

    private fun publishQueueRemoved(queuedBuild: SQueuedBuild, user: User?, comment: String) {
        val promotion = queuedBuild.buildPromotion
        val associated = promotion.associatedBuild

        // The build left the queue to actually RUN: a running SBuild
        // exists and buildStarted / buildFinished own the Check Run row.
        if (associated != null && !associated.isFinished) return

        val ctx = resolveContext(promotion.buildType, promotion.revisions) ?: return

        if (associated != null) {
            // A FINISHED build is attached to this promotion.
            if (associated.buildPromotion.id != promotion.id) {
                // Queue optimization: this promotion was satisfied by an
                // EQUIVALENT build (different promotion) that owns its own
                // Check Run row. Nothing to post.
                return
            }
            // This promotion's OWN build is finished although it never ran
            // to completion normally (it left the queue): it "failed to
            // start", typically because a snapshot dependency failed.
            // Report its real outcome — the same conclusion buildFinished
            // reports — so the row reaches a terminal state (a failed
            // dependency => "Build failed", red, blocks the merge).
            // `isInterrupted` is SRunningBuild-only; on a finished SBuild
            // a non-null canceledInfo is the equivalent signal.
            val mapping = mapBuildOutcome(associated.buildStatus, associated.canceledInfo != null)
            val summary = associated.statusDescriptor.text.orEmpty().takeIf { it.isNotBlank() }
                ?: comment.takeIf { it.isNotBlank() }
                ?: "Build did not run to completion (a snapshot dependency likely failed)."
            val request = CheckRunRequest(
                name = checkRunName(ctx.buildType),
                headSha = ctx.headSha,
                status = CheckRunStatus.COMPLETED,
                conclusion = mapping.conclusion,
                outputTitle = mapping.title,
                outputSummary = truncateSummary(summary),
                detailsUrl = safeUrl { webLinks.getViewResultsUrl(associated) },
            )
            post(ctx, request, "queue-removed/finished (${mapping.conclusion.apiValue})")
            return
        }

        // No associated build at all. A user removing a queued build is a
        // genuine cancellation — report it so the row does not stay stuck.
        if (user != null) {
            val summary = comment.takeIf { it.isNotBlank() }
                ?: "Build was cancelled before it started."
            val request = CheckRunRequest(
                name = checkRunName(ctx.buildType),
                headSha = ctx.headSha,
                status = CheckRunStatus.COMPLETED,
                conclusion = CheckRunConclusion.CANCELLED,
                outputTitle = "Cancelled before start",
                outputSummary = truncateSummary(summary),
                detailsUrl = safeUrl { webLinks.getConfigurationHomePageUrl(ctx.buildType) },
            )
            post(ctx, request, "queue-removed/cancelled")
            return
        }

        // System removal with no associated build. In a dependency fan-out
        // the plugin (and TeamCity's chain optimization) create duplicate
        // queued promotions of the shared dependency; tearing those down
        // fires this event with no record — and the REAL build already
        // reported via buildFinished or its own finished record above.
        // Posting a generic status here would clobber that real result
        // under the same Check Run name (this is what flipped a
        // genuinely-failed build to "Build could not start"). The same is
        // true for gate-suppressed builds our queue cleaner removed (it
        // owns their Skipped row). So stay silent: a build that genuinely
        // failed to start keeps its own finished record, handled above.
        LOG.debug("Ignoring system queue removal with no associated build for ${ctx.buildType.externalId} (${promotion.branch?.name})")
    }

    private fun post(ctx: PrBuildContext, request: CheckRunRequest, label: String) {
        if (serverSettings.dryRun()) {
            LOG.info("[dry-run] would POST $label Check Run for ${ctx.repo.slug}@${ctx.headSha}")
            return
        }
        val ok = gitHubClient.postCheckRun(ctx.accessToken, ctx.repo, request, ctx.apiBase)
        if (!ok) {
            metrics.inc(io.github.dlachouette.teamcity.github.web.BridgeMetrics.CHECK_RUNS_FAILED)
            LOG.warn("Check Run POST ($label) failed for ${ctx.repo.slug}@${ctx.headSha}")
        } else {
            metrics.inc(io.github.dlachouette.teamcity.github.web.BridgeMetrics.CHECK_RUNS_POSTED)
            LOG.info("Published $label Check Run for ${ctx.repo.slug}@${ctx.headSha}")
        }
    }

    private fun resolveContext(buildType: SBuildType?, revisions: List<BuildRevision>): PrBuildContext? {
        if (buildType == null) return null
        val config = BridgeFeatureReader.read(buildType) ?: return null
        // Publication depends on this flag and on nothing else — not on what
        // started the build, not on the ref.
        if (!config.publishChecks) return null
        if (!serverSettings.isRepoAllowed(config.repo.slug)) return null

        val headSha = revisions.firstOrNull()?.revision?.takeIf { it.isNotBlank() } ?: return null
        // TokenResolver already logs the cause (rate-limited).
        val access = tokenResolver.resolveAccessToken(buildType.project, config.connectionId, config.repo) ?: return null
        return PrBuildContext(
            repo = config.repo,
            buildType = buildType,
            config = config,
            headSha = headSha,
            accessToken = access.token,
            apiBase = access.apiBase,
        )
    }

    private data class PrBuildContext(
        val repo: RepoCoords,
        val buildType: SBuildType,
        val config: BridgeFeatureConfig,
        val headSha: String,
        val accessToken: String,
        val apiBase: String,
    )

    // Artefact links, so a reviewer or a tester reaches the installer or
    // the package straight from the PR instead of hunting for the build in
    // TeamCity. Top-level files only, capped, and skipped entirely when the
    // build produced nothing.
    private fun artifactSection(build: SBuild): String? {
        if (!serverSettings.artifactLinksEnabled()) return null
        val artifactsUrl = artifactsUrl(build) ?: return null
        val names = topLevelArtifactNames(build)
        if (names.isEmpty()) return null
        return buildString {
            append("### Artifacts\n\n")
            names.forEach { append("- [").append(it).append("](").append(artifactsUrl).append(")\n") }
            if (names.size == MAX_ARTIFACTS) append("- …\n")
        }
    }

    private fun artifactsUrl(build: SBuild): String? =
        if (!build.isArtifactsExists) null else safeUrl { webLinks.getViewArtifactsUrl(build) }

    private fun topLevelArtifactNames(build: SBuild): List<String> = try {
        build.getArtifacts(BuildArtifactsViewMode.VIEW_DEFAULT).rootArtifact.children
            .asSequence()
            .filterNot { it.isDirectory }
            .map { it.name }
            .take(MAX_ARTIFACTS)
            .toList()
    } catch (e: Exception) {
        LOG.debug("Could not list artifacts of build ${build.buildId}: ${e.message}")
        emptyList()
    }

    companion object {
        private val LOG = Logger.getInstance(BuildStatusCheckRunPublisher::class.java.name)

        // The VCS revision of a freshly-enqueued build is resolved by a
        // background task, so it may not be ready when buildTypeAddedToQueue
        // fires. Retry up to this many times, this far apart, before giving
        // up on the "Queued" Check Run (~a few seconds total). buildStarted
        // still posts "in_progress" regardless.
        const val MAX_QUEUED_ATTEMPTS: Int = 8
        const val QUEUED_RETRY_DELAY_MS: Long = 750L

        // Pure decision for the "queued" publish, given whether the VCS
        // revision is resolved yet and which attempt this is. Testable
        // without TC SDK fixtures. Lifecycle aborts (build already started /
        // left the queue) and the "not our build" gate are handled by the
        // caller before this is reached.
        fun decideQueuedAction(revisionReady: Boolean, attempt: Int): QueuedAction = when {
            revisionReady -> QueuedAction.PUBLISH
            attempt >= MAX_QUEUED_ATTEMPTS -> QueuedAction.GIVE_UP
            else -> QueuedAction.RETRY
        }

        // GitHub limits output.summary to 65535 characters. Truncate
        // conservatively to leave headroom for the ellipsis.
        const val SUMMARY_MAX: Int = 60_000

        // Cap how many failure reasons we list in the Check Run body.
        const val MAX_FAILURE_REASONS: Int = 30

        // Top-level artifact files listed in the completed Check Run.
        const val MAX_ARTIFACTS: Int = 10

        fun truncateSummary(text: String): String =
            if (text.length <= SUMMARY_MAX) text
            else text.take(SUMMARY_MAX) + "\n\n... (truncated)"

        // Build a Markdown body listing the build's failure reasons, for
        // the Check Run `output.text`. Returns null when there are none
        // (passing builds), so the field is omitted. Each reason is a
        // BuildProblemData carrying a human-readable description.
        // Concatenate the optional Markdown sections of output.text.
        fun joinSections(vararg sections: String?): String? =
            sections.filterNot { it.isNullOrBlank() }.joinToString("\n").takeIf { it.isNotBlank() }

        fun failureDetails(build: SBuild): String? {
            val reasons = try {
                build.failureReasons
            } catch (e: Exception) {
                LOG.debug("Could not read failure reasons for build ${build.buildId}: ${e.message}")
                return null
            }
            if (reasons.isEmpty()) return null
            val body = buildString {
                append("### Failure details\n\n")
                reasons.take(MAX_FAILURE_REASONS).forEach { r ->
                    val desc = r.description?.takeIf { it.isNotBlank() } ?: r.type
                    append("- ").append(desc).append('\n')
                }
                if (reasons.size > MAX_FAILURE_REASONS) {
                    append("\n_…and ${reasons.size - MAX_FAILURE_REASONS} more (see the TeamCity build log)._")
                }
            }
            return truncateSummary(body)
        }

        // Pure mapping helper — testable without TC SDK fixtures.
        // Encodes the policy: warning is still successful in TC's
        // model (it does not fail the build by default), so we report
        // SUCCESS. Interrupted builds map to CANCELLED regardless of
        // the underlying status.
        fun mapBuildOutcome(status: Status, isInterrupted: Boolean): BuildOutcomeMapping {
            if (isInterrupted) {
                return BuildOutcomeMapping(CheckRunConclusion.CANCELLED, "Build cancelled")
            }
            return when {
                status.isSuccessful -> BuildOutcomeMapping(CheckRunConclusion.SUCCESS, "Build passed")
                status.isFailed -> BuildOutcomeMapping(CheckRunConclusion.FAILURE, "Build failed")
                else -> BuildOutcomeMapping(CheckRunConclusion.NEUTRAL, "Build status: ${status.text}")
            }
        }
    }
}

data class BuildOutcomeMapping(
    val conclusion: CheckRunConclusion,
    val title: String,
)

// What to do with a "queued" publish attempt whose build is still in the
// queue and belongs to us: post now, retry later, or give up.
enum class QueuedAction { PUBLISH, RETRY, GIVE_UP }
