package io.github.dlachouette.teamcity.github.report

import com.intellij.openapi.diagnostic.Logger
import io.github.dlachouette.teamcity.github.api.CheckRunAnnotation
import io.github.dlachouette.teamcity.github.api.CheckRunConclusion
import io.github.dlachouette.teamcity.github.api.CheckRunRequest
import io.github.dlachouette.teamcity.github.api.CheckRunStatus
import io.github.dlachouette.teamcity.github.api.GitHubClient
import io.github.dlachouette.teamcity.github.api.RepoCoords
import io.github.dlachouette.teamcity.github.api.TokenResolver
import io.github.dlachouette.teamcity.github.api.PrInfoCache
import io.github.dlachouette.teamcity.github.feature.AnnotationGate
import io.github.dlachouette.teamcity.github.feature.BridgeFeatureConfig
import io.github.dlachouette.teamcity.github.feature.BridgeFeatureReader
import io.github.dlachouette.teamcity.github.feature.BridgeProjectParams
import io.github.dlachouette.teamcity.github.feature.BridgeGate
import io.github.dlachouette.teamcity.github.feature.GateDecision
import io.github.dlachouette.teamcity.github.queue.QueueCleanupPolicy
import io.github.dlachouette.teamcity.github.feature.resolvesPrFromCommit
import jetbrains.buildServer.BuildProblemTypes
import jetbrains.buildServer.messages.Status
import jetbrains.buildServer.serverSide.BuildPromotion
import jetbrains.buildServer.serverSide.BuildRevision
import jetbrains.buildServer.serverSide.BuildServerAdapter
import jetbrains.buildServer.serverSide.SBuild
import jetbrains.buildServer.serverSide.SBuildServer
import jetbrains.buildServer.serverSide.SBuildType
import jetbrains.buildServer.serverSide.SQueuedBuild
import jetbrains.buildServer.serverSide.SRunningBuild
import jetbrains.buildServer.serverSide.STestRun
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
//
// Personal builds are excluded at every one of those transitions — see
// `isPersonal`.
class BuildStatusCheckRunPublisher(
    buildServer: SBuildServer,
    private val tokenResolver: TokenResolver,
    private val gitHubClient: GitHubClient,
    private val webLinks: WebLinks,
    private val prInfoCache: PrInfoCache,
    private val gateContextResolver: io.github.dlachouette.teamcity.github.feature.GateContextResolver,
    private val serverSettings: io.github.dlachouette.teamcity.github.config.BridgeServerSettings,
    private val metrics: io.github.dlachouette.teamcity.github.web.BridgeMetrics,
    private val executorServices: ExecutorServices,
) : BuildServerAdapter() {

    init {
        buildServer.addListener(this)
    }

    override fun buildTypeAddedToQueue(queuedBuild: SQueuedBuild) {
        if (isPersonal(queuedBuild.buildPromotion)) return
        try {
            attemptPublishQueued(queuedBuild, attempt = 1)
        } catch (e: Exception) {
            LOG.warn("Failed publishing queued Check Run for ${queuedBuild.buildType.externalId}: ${e.message}", e)
        }
    }

    override fun buildStarted(build: SRunningBuild) {
        if (isPersonal(build.buildPromotion)) return
        try {
            publishInProgress(build)
        } catch (e: Exception) {
            LOG.warn("Failed publishing in-progress Check Run for ${build.buildTypeExternalId} #${build.buildId}: ${e.message}", e)
        }
    }

    override fun buildFinished(build: SRunningBuild) {
        if (isPersonal(build.buildPromotion)) return
        try {
            publishCompleted(build)
        } catch (e: Exception) {
            LOG.warn("Failed publishing completed Check Run for ${build.buildTypeExternalId} #${build.buildId}: ${e.message}", e)
        }
    }

    override fun buildInterrupted(build: SRunningBuild) {
        if (isPersonal(build.buildPromotion)) return
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
        if (isPersonal(queuedBuild.buildPromotion)) return
        try {
            publishQueueRemoved(queuedBuild, user, comment)
        } catch (e: Exception) {
            LOG.warn("Failed publishing Check Run for queue removal of ${queuedBuild.buildType.externalId}: ${e.message}", e)
        }
    }

    // A personal build verifies a patch that exists only in one developer's
    // working copy: its outcome describes that patch, not the commit GitHub
    // knows, so it must never speak for that commit — no "Queued", no
    // "Building", no conclusion, no PR comment.
    //
    // Before this guard a personal build reported like any other, and
    // triggering one by hand left a Check Run stuck on "Queued" on the PR
    // for good — the reported symptom. Keeping such a row up to date is not
    // the fix: the row must never be opened.
    //
    // Publication is the ONLY thing a personal build loses. It still resolves
    // its pull request and receives the PR parameters and the `pr-N` /
    // `draft`/`ready` tags (PrBuildEnricher, PrParameterProvider) — a
    // developer running a patch against a PR wants that context — and it is
    // outside queue dedup rather than subject to it.
    private fun isPersonal(promotion: BuildPromotion): Boolean {
        if (publishesFor(personal = promotion.isPersonal)) return false
        LOG.debug("Skipping Check Run publication for personal build of ${promotion.buildType?.externalId}")
        return true
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
            // From here on GitHub counts the elapsed time itself.
            startedAt = build.startDate?.time?.let { BuildTimeline.iso(it) },
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
        val failure = classifyFailure(build)
        val mapping = refineForFailureCause(
            mapBuildOutcome(build.buildStatus, build.isInterrupted),
            failure,
            serverSettings.infraFailureNeutralEnabled(),
        )
        val tests = testOutcomeOf(build)
        val request = CheckRunRequest(
            name = checkRunName(ctx.buildType),
            headSha = ctx.headSha,
            status = CheckRunStatus.COMPLETED,
            conclusion = mapping.conclusion,
            outputTitle = titleWithTests(mapping.title, tests?.counts),
            // The summary is the highest place a Check Run lets us write: GitHub
            // renders its own header, then `output.title`, then this, then the
            // body. The timing therefore goes HERE to sit as close to the top as
            // the API allows — nothing can precede the title.
            outputSummary = truncateSummary(
                joinSections(
                    // First: whether the commit was even judged. A reviewer
                    // reading "failed" needs to know it was our CI, not them.
                    infrastructureNote(failure, mapping.conclusion),
                    timingBlock(build),
                    statusTextOf(build),
                ).orEmpty().ifBlank { mapping.title },
            ),
            detailsUrl = safeUrl { webLinks.getViewResultsUrl(build) },
            // Then, most-asked question first: why did it fail, what did the
            // tests say, what did it produce, and where to go for the rest.
            outputText = joinSections(
                // The test section, when there is one, says "12 failed (12 new)"
                // and names them: TeamCity's own "12 failed tests detected"
                // above it is the same sentence with less in it.
                failureDetails(build, testsReported = tests != null),
                tests?.let { TestReport.section(it.counts, it.failed) },
                artifactSection(build),
                teamCityLink(build),
            ),
            annotations = annotationsFor(build, ctx.config),
            startedAt = build.startDate?.time?.let { BuildTimeline.iso(it) },
            completedAt = BuildTimeline.iso(finishInstantOf(build)),
        )
        post(ctx, request, "completed (${mapping.conclusion.apiValue})")
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
            val failure = classifyFailure(associated)
            val mapping = refineForFailureCause(
                mapBuildOutcome(associated.buildStatus, associated.canceledInfo != null),
                failure,
                serverSettings.infraFailureNeutralEnabled(),
            )
            val summary = joinSections(
                infrastructureNote(failure, mapping.conclusion),
                associated.statusDescriptor.text.orEmpty().takeIf { it.isNotBlank() },
            )
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

    private data class TestOutcome(val counts: TestCounts, val failed: List<FailedTestRun>)

    // The build's test outcome, or null when the feature is off, the build ran
    // no test at all, or TeamCity could not produce the statistics.
    //
    // `shortStatistics` is the cheap read: the counts plus the failing runs,
    // without materialising every passed test of a 10 000-test suite. The
    // failing list it returns already excludes muted tests, which is what we
    // want — a muted failure is a deliberate exception, reported as a count
    // and not as a failure someone should chase.
    private fun testOutcomeOf(build: SBuild): TestOutcome? {
        if (!serverSettings.checkRunTestStatsEnabled()) return null
        val stats = try {
            build.shortStatistics
        } catch (e: Exception) {
            LOG.debug("Could not read test statistics of build ${build.buildId}: ${e.message}")
            return null
        }
        val counts = TestCounts(
            total = stats.allTestCount,
            passed = stats.passedTestCount,
            failed = stats.failedTestCount,
            ignored = stats.ignoredTestCount,
            muted = stats.mutedTestsCount,
            newFailed = stats.newFailedCount,
        )
        if (!counts.ran) return null
        val failed = try {
            // One page beyond what we list, so "…and N more" is honest without
            // walking a suite where everything failed.
            stats.failedTests.asSequence().take(TestReport.MAX_LISTED + 1).map { failedRunOf(it) }.toList()
        } catch (e: Exception) {
            LOG.debug("Could not read the failing tests of build ${build.buildId}: ${e.message}")
            emptyList()
        }
        return TestOutcome(counts, failed)
    }

    private fun failedRunOf(run: STestRun): FailedTestRun {
        val name = run.test.name.let { TestReport.displayName(it.asString, it.nameWithoutSuite, it.suite) }
        return FailedTestRun(
            name = name,
            newFailure = run.isNewFailure,
            durationMillis = run.duration.toLong(),
            // Where it broke, for a failure this build did not introduce: it
            // tells a reviewer "not you" in one glance.
            firstFailedInBuildNumber = try {
                run.firstFailed?.buildNumber
            } catch (e: Exception) {
                null
            },
            failureText = try {
                failureTextOf(run, name)
            } catch (e: Exception) {
                null
            },
        )
    }

    // The most informative failure text TeamCity has for this run, or null when
    // it has none.
    //
    // `shortStacktrace` is the right answer for a runner that produces one
    // (JUnit, NUnit, pytest…). An importer that does not — CTest, a hand-rolled
    // XML report — leaves both it and `statusText` at a bare "Failure", and the
    // actual output of the test lives only in `fullText`. So the cheap reads
    // come first and `fullText` is paid for only when they said nothing: it
    // materialises the whole output of the test, which for a chatty test is
    // not small.
    private fun failureTextOf(run: STestRun, name: String): String? {
        val info = run.failureInfo
        sequenceOf(info?.shortStacktrace, info?.stacktraceMessage, run.statusText).forEach { candidate ->
            TestReport.informativeFailure(candidate, name)?.let { return it }
        }
        return TestReport.informativeFailure(run.fullText, name)
    }

    // "Ran 7m 12s on `agent-3`, after 4m queued (3m waiting for its
    // dependencies, 1m for a free agent)." — null when the feature is off or
    // the build never started.
    // TeamCity's own one-line verdict ("Tests passed: 5", "Exit code 1"), when
    // it says something the title does not.
    //
    // At `buildFinished` the status descriptor has NOT been recomputed yet —
    // same lag as the null `finishDate` — so it still reads "Running", which is
    // how a finished, green Check Run ended up summarised as "Running". A stale
    // running text is dropped rather than published: the title already carries
    // the verdict, so nothing is lost.
    private fun statusTextOf(build: SBuild): String? = try {
        build.statusDescriptor.text?.takeIf { isInformativeStatusText(it) }
    } catch (e: Exception) {
        null
    }

    private fun timingBlock(build: SBuild): String? {
        if (!serverSettings.checkRunTimingsEnabled()) return null
        val timings = BuildTimeline.compute(
            queuedAt = build.queuedDate?.time,
            startedAt = build.startDate?.time,
            finishedAt = finishInstantOf(build),
            lastDependencyFinishedAt = lastDependencyFinish(build.buildPromotion),
            agentWaitHintMillis = agentWaitHint(build),
            hasDependencies = hasDependencies(build.buildPromotion),
        ) ?: return null
        // No heading: in the summary, right under the title, the three lines are
        // the first thing on the row and need no label.
        return BuildTimeline.describeBlock(timings, agentNameOf(build))
    }

    // Where to go for everything a Check Run cannot hold: the log, the tests
    // tab, the changes. `details_url` already points here, but that link lives
    // in GitHub's chrome and gets missed — a link in the body is one the reader
    // actually sees.
    private fun teamCityLink(build: SBuild): String? {
        val url = safeUrl { webLinks.getViewResultsUrl(build) } ?: return null
        return "[Build #${build.buildNumber} in TeamCity]($url)"
    }

    private fun hasDependencies(promotion: BuildPromotion): Boolean = try {
        promotion.dependencies.isNotEmpty()
    } catch (e: Exception) {
        false
    }

    // How much of the queue time TeamCity itself attributes to there being no
    // free compatible agent — the only way to tell a real agent shortage from
    // the rest of the queue time (collecting changes, queue synchronisation,
    // the distribution process), which the dates cannot distinguish.
    //
    // TeamCity records the wait per reason as build statistic values, but the
    // open API declares NEITHER the key naming NOR the unit, so this matches
    // defensively and errs low: an unrecognised layout returns null and the
    // whole remainder becomes unattributed `misc`, reported as a wait with no
    // stated cause rather than as an agent shortage. The debug line lists the
    // keys the build actually carries, which is what to look at on a live
    // server if the agent share never shows up.
    private fun agentWaitHint(build: SBuild): Long? {
        val values = try {
            build.statisticValues
        } catch (e: Exception) {
            LOG.debug("Could not read the statistic values of build ${build.buildId}: ${e.message}")
            return null
        }
        val waitReasons = values.filterKeys { it.contains("waitreason", ignoreCase = true) }
        if (waitReasons.isEmpty()) {
            LOG.debug("Build ${build.buildId} carries no queue wait-reason statistic; " +
                "keys present: ${values.keys.sorted()}")
            return null
        }
        val agentReasons = waitReasons.filterKeys { key -> AGENT_WAIT_MARKERS.any { key.contains(it, ignoreCase = true) } }
        if (agentReasons.isEmpty()) {
            LOG.debug("Build ${build.buildId} waited in the queue for reasons none of which names agent " +
                "availability: ${waitReasons.keys.sorted()}")
            return null
        }
        LOG.debug("Build ${build.buildId} agent-wait statistics: $agentReasons")
        return agentReasons.values.sumOf { it.toLong() }.takeIf { it > 0 }
    }

    // When the build stopped working.
    //
    // `finishDate` is NOT set yet on the build object handed to buildFinished —
    // TeamCity fills it while persisting the finished record, after the
    // listeners run. Reading it there yields null, which made every Check Run
    // report a run time of zero ("Ran <1s") no matter how long the build
    // actually took, and dropped `completed_at` from the payload.
    //
    // We are called from buildFinished/buildInterrupted, i.e. at the moment the
    // build stops, so "now" is that instant to within the callback's own
    // latency. `Build.getDuration()` is deliberately NOT used as the fallback:
    // it is expressed in seconds, and one wrong unit here would misreport every
    // build by a factor of a thousand.
    private fun finishInstantOf(build: SBuild): Long =
        build.finishDate?.time ?: System.currentTimeMillis()

    // The instant the LAST snapshot dependency finished, i.e. the moment this
    // build became eligible for an agent. Only DIRECT dependencies are read: a
    // transitive one necessarily finished before the direct one waiting on it,
    // so it cannot move the maximum.
    private fun lastDependencyFinish(promotion: BuildPromotion): Long? = try {
        promotion.dependencies.asSequence()
            .mapNotNull { it.dependOn.associatedBuild?.finishDate?.time }
            .maxOrNull()
    } catch (e: Exception) {
        LOG.debug("Could not read the dependencies of promotion ${promotion.id}: ${e.message}")
        null
    }

    private fun agentNameOf(build: SBuild): String? = try {
        if (build.isAgentLessBuild) null else build.agent.name.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        // A finished build whose agent is long gone: the duration still holds.
        null
    }

    // Is this build red because of the code, or because CI broke? Read from
    // the *types* of the build problems TeamCity already reported — the same
    // list `failureDetails` describes in the body. A build that reported no
    // problem, or whose problems cannot be read, classifies as CODE, i.e. the
    // conclusion it had before this existed.
    private fun classifyFailure(build: SBuild): FailureClassification {
        val types = try {
            build.failureReasons.map { it.type }
        } catch (e: Exception) {
            LOG.debug("Could not read failure reasons of build ${build.buildId} for classification: ${e.message}")
            return FailureClassification(FailureKind.CODE)
        }
        val classification = FailureClassifier.classify(types)
        if (classification.kind != FailureKind.CODE) {
            LOG.debug("Build ${build.buildId} failure classified as ${classification.kind} " +
                "(${classification.cause}) from problem types $types")
        }
        return classification
    }

    // Line-level annotations for the failing diagnostics, so the PR's diff
    // shows them where they happened. Empty for a build that reported no
    // problem, and for problems whose file is not in the checkout.
    private fun annotationsFor(build: SBuild, config: BridgeFeatureConfig): List<CheckRunAnnotation> {
        if (!annotationsAllowed(build, config)) return emptyList()
        val checkoutDir = try {
            build.parametersProvider.get(CHECKOUT_DIR_PARAM)
        } catch (e: Exception) {
            null
        }
        val descriptions = try {
            build.failureReasons.mapNotNull { it.description }
        } catch (e: Exception) {
            LOG.debug("Could not read failure reasons of build ${build.buildId} for annotations: ${e.message}")
            emptyList()
        }
        val fromProblems = BuildProblemAnnotations.parse(descriptions, checkoutDir)
        if (fromProblems.isNotEmpty()) return fromProblems
        return annotationsFromLog(build, checkoutDir)
    }

    // Three levels have a veto over writing on somebody's diff: the server, any
    // project in the chain, and the build configuration. `AnnotationGate` holds
    // the rule; this reads the chain.
    //
    // Own parameters, project by project from the root down — deliberately not
    // `project.parameters`, which resolves to the nearest definition and would
    // let a sub-project overrule the `no` of its parent.
    private fun annotationsAllowed(build: SBuild, config: BridgeFeatureConfig): Boolean {
        val chain = try {
            build.buildType?.project?.projectPath
                ?.map { it.ownParameters[BridgeProjectParams.ANNOTATIONS_ENABLED] }
                .orEmpty()
        } catch (e: Exception) {
            LOG.debug("Could not read the project chain of build ${build.buildId} for annotations: ${e.message}")
            emptyList()
        }
        return AnnotationGate.enabled(
            serverEnabled = serverSettings.checkRunAnnotationsEnabled(),
            projectChain = chain,
            feature = config.annotateDiff,
        )
    }

    // The fallback: read the failed build's log.
    //
    // A Command Line runner reports "Process exited with code 1" and nothing
    // else, so for a CMake/ninja build the diagnostics only ever exist in the
    // log — which is exactly the case this feature was supposed to serve.
    //
    // Only for a build that did not succeed (a green build has no diagnostic
    // worth pinning, and this is the expensive path), and the iterator is
    // consumed lazily so the read stops at the 50th annotation or the line
    // budget, whichever comes first.
    private fun annotationsFromLog(build: SBuild, checkoutDir: String?): List<CheckRunAnnotation> {
        if (!serverSettings.checkRunLogScanEnabled()) return emptyList()
        if (build.buildStatus.isSuccessful) return emptyList()
        return try {
            val messages = build.buildLog.messagesIterator
            val lines = sequence {
                while (messages.hasNext()) {
                    yield(messages.next()?.text.orEmpty())
                }
            }
            BuildProblemAnnotations.scan(lines, checkoutDir).also {
                if (it.isNotEmpty()) {
                    LOG.debug("Scanned the log of build ${build.buildId}: ${it.size} annotation(s)")
                }
            }
        } catch (e: Exception) {
            LOG.debug("Could not scan the log of build ${build.buildId} for annotations: ${e.message}")
            emptyList()
        }
    }

    // Artefact links, so a reviewer or a tester reaches the installer or the
    // package straight from the PR instead of hunting for the build in
    // TeamCity. Every link is a **direct download**, not the artifacts tab:
    // clicking it downloads the file.
    //
    // Top-level files only, capped, and skipped entirely when the build
    // produced nothing. When there are more files than the cap, a final link
    // to the artifacts tab covers the rest.
    private fun artifactSection(build: SBuild): String? {
        if (!serverSettings.artifactLinksEnabled()) return null
        val links = artifactLinks(build)
        if (links.isEmpty()) return null
        return buildString {
            append("### Artifacts\n\n")
            links.forEach { append("- [").append(it.name).append("](").append(it.url).append(")\n") }
            if (links.size == MAX_ARTIFACTS) {
                artifactsPageUrl(build)?.let { append("- [all artifacts…](").append(it).append(")\n") }
            }
        }
    }

    // Direct download links for the build's top-level artifact files.
    //
    // The root URL is read per project (`getRootUrlByProjectExternalId`), not
    // globally: TeamCity lets a project override it, and a link built from the
    // wrong host would 404 for exactly the people we are trying to help.
    internal fun artifactLinks(build: SBuild): List<ArtifactLink> {
        if (!build.isArtifactsExists) return emptyList()
        val projectId = build.buildType?.project?.externalId ?: return emptyList()
        val root = safeUrl { webLinks.getRootUrlByProjectExternalId(projectId) } ?: return emptyList()
        return topLevelArtifactNames(build).map { name ->
            ArtifactLink(name, artifactDownloadUrl(root, build, name))
        }
    }

    private fun artifactsPageUrl(build: SBuild): String? =
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

        // The publication rule for personal builds, as a pure predicate:
        // they never report. Deliberately independent of `publishChecks`,
        // of the trigger and of the ref — a personal build is not a state
        // of the repository, so no GitHub row may describe it.
        fun publishesFor(personal: Boolean): Boolean = !personal

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

        // TeamCity's artifact download URL — the long-standing
        // `/repository/download/<btExternalId>/<buildId>:id/<path>` contract,
        // the same one artifact dependencies use. Not the artifacts *tab*: the
        // point is that clicking the link downloads the file.
        fun artifactDownloadUrl(rootUrl: String, build: SBuild, path: String): String =
            "${rootUrl.trimEnd('/')}/repository/download/" +
                "${build.buildTypeExternalId}/${build.buildId}:id/${encodeArtifactPath(path)}"

        // Percent-encode each segment, keeping the separators. `URLEncoder`
        // targets form bodies, so its `+` for a space must become `%20`.
        fun encodeArtifactPath(path: String): String =
            path.split('/').joinToString("/") { segment ->
                java.net.URLEncoder.encode(segment, java.nio.charset.StandardCharsets.UTF_8)
                    .replace("+", "%20")
            }

        // GitHub limits output.summary to 65535 characters. Truncate
        // conservatively to leave headroom for the ellipsis.
        const val SUMMARY_MAX: Int = 60_000

        // Cap how many failure reasons we list in the Check Run body.
        const val MAX_FAILURE_REASONS: Int = 30

        // Top-level artifact files listed in the completed Check Run.
        const val MAX_ARTIFACTS: Int = 10

        // TeamCity's own parameter for the agent-side checkout directory,
        // used to turn an absolute diagnostic path into a repo-relative one.
        private const val CHECKOUT_DIR_PARAM: String = "teamcity.build.checkoutDir"

        // GitHub caps output.title at 255 characters.
        const val TITLE_MAX: Int = 255

        // Is TeamCity's status text worth publishing on a FINISHED build? Not
        // when it is the running-state text the descriptor still carries at
        // `buildFinished` ("Running", "Running: step 2 of 5"): on a completed
        // Check Run that is simply wrong. Everything else — "Tests passed: 5",
        // "Exit code 1", a custom `##teamcity[buildStatus text=…]` — is exactly
        // what we want to relay, since carrying the real status text instead of
        // GitHub's canned wording is the point of this plugin.
        fun isInformativeStatusText(text: String): Boolean {
            val trimmed = text.trim()
            return trimmed.isNotEmpty() && !trimmed.startsWith("Running", ignoreCase = true)
        }

        // Fragments that identify a queue wait-reason statistic as "no agent
        // could take this build". TeamCity's own wording is of the shape "There
        // are no idle compatible agents which can run this build"; matching on
        // the words rather than on a full string keeps this working across
        // wordings and locales, and anything unmatched simply stays
        // unattributed.
        val AGENT_WAIT_MARKERS: List<String> = listOf("agent", "compatible")

        // "Build failed" + the test verdict = the single line GitHub shows in
        // the PR's merge box. Unchanged when the build ran no test, which is
        // most build configurations.
        fun titleWithTests(base: String, counts: TestCounts?): String {
            val suffix = counts?.let { TestReport.titleSuffix(it) } ?: return base
            return "$base — $suffix".take(TITLE_MAX)
        }

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

        fun failureDetails(build: SBuild, testsReported: Boolean = false): String? {
            val all = try {
                build.failureReasons
            } catch (e: Exception) {
                LOG.debug("Could not read failure reasons for build ${build.buildId}: ${e.message}")
                return null
            }
            // Drop the "N failed tests detected" problem when the test section
            // is going to say it properly, with the names. Kept when there is
            // no section (stats off or unreadable): then it is all we have.
            val reasons = if (testsReported) all.filterNot { it.type == BuildProblemTypes.TC_FAILED_TESTS_TYPE } else all
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

        // Name the cause in the title, and — for an infrastructural failure —
        // decide whether it still blocks the merge.
        //
        // Only a FAILURE is refined: a build can report a problem and still be
        // green (that is what TeamCity's SNAPSHOT_DEPENDENCY_ERROR_BUILD_PROCEEDS
        // is for), and a cancelled build's conclusion is not up for debate.
        //
        // The title always states the cause, whatever `infraNeutral` says: what
        // broke is a fact, whether it should block a merge is a policy. GitHub
        // treats a required check concluding `neutral` as satisfied, so
        // `infraNeutral` really does unblock the merge — which is the point,
        // and why it is a checkbox.
        fun refineForFailureCause(
            mapping: BuildOutcomeMapping,
            failure: FailureClassification,
            infraNeutral: Boolean,
        ): BuildOutcomeMapping {
            if (mapping.conclusion != CheckRunConclusion.FAILURE) return mapping
            val cause = failure.cause
            return when (failure.kind) {
                FailureKind.CODE -> mapping
                FailureKind.INFRASTRUCTURE -> BuildOutcomeMapping(
                    conclusion = if (infraNeutral) CheckRunConclusion.NEUTRAL else CheckRunConclusion.FAILURE,
                    title = titled("Infrastructure failure", cause),
                )
                // Still red: the dependency may have failed on this PR's code.
                FailureKind.DEPENDENCY -> BuildOutcomeMapping(
                    conclusion = CheckRunConclusion.FAILURE,
                    title = titled("Build failed", cause),
                )
            }
        }

        private fun titled(base: String, cause: String?): String =
            (if (cause.isNullOrBlank()) base else "$base: $cause").take(TITLE_MAX)

        // The sentence a reviewer needs above everything else when the build
        // never really judged the commit. Null for a code failure — there the
        // title, the tests and the failure details already say it all — and
        // null for anything that is not a failure.
        fun infrastructureNote(failure: FailureClassification, conclusion: CheckRunConclusion): String? {
            if (!failure.infrastructural) return null
            if (conclusion != CheckRunConclusion.NEUTRAL && conclusion != CheckRunConclusion.FAILURE) return null
            val cause = failure.cause?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
            val verdict =
                if (conclusion == CheckRunConclusion.NEUTRAL) "Reported as neutral, so it does not block the merge."
                // The default. Not phrased as "this server is configured to":
                // holding the merge until a human has looked is the ordinary
                // behaviour, not an unusual choice somebody made.
                else "Still reported as a failure, so the merge stays blocked until it is re-run."
            return "> **CI infrastructure failure, not a problem with the code.** The build could not " +
                "run to completion for a reason outside the repository$cause, so this commit has not " +
                "been verified — re-run the build. $verdict"
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
