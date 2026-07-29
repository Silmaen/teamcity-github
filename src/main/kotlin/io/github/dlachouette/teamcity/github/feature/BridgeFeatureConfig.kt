package io.github.dlachouette.teamcity.github.feature

import io.github.dlachouette.teamcity.github.api.RepoCoords
import jetbrains.buildServer.serverSide.SBuildType

// Project-level parameter keys. Set on the (sub-)project containing
// the opt-in BuildTypes; TC's parameter inheritance makes them
// visible via `buildType.project.parameters` (NOT `buildType.parameters`,
// which only resolves own + template-inherited — see
// BridgeFeatureReader for details).
//
// Two independent "trigger paths" can be enabled per project:
//   - branchTrigger: builds on non-PR branches (main, Release/*, …)
//   - prTrigger:     builds on PR branches (pull/N)
// Each path has its own enable toggle + branch list. Empty branch
// list = match every branch on that path. A disabled path makes the bridge
// MUTE for it (no trigger, no Check Run) — never destructive: an explicit
// build is left alone, it is simply not reported.
object BridgeProjectParams {
    const val REPO: String = "teamcity.github.bridge.repo"
    const val CONNECTION_ID: String = "teamcity.github.bridge.connectionId"

    // Which ref a PR build runs on — see `PrBuildRef`.
    const val PR_BUILD_REF: String = "teamcity.github.bridge.prBuildRef"

    const val BRANCH_TRIGGER_ENABLED: String = "teamcity.github.bridge.branchTrigger.enabled"
    const val BRANCH_TRIGGER_BRANCHES: String = "teamcity.github.bridge.branchTrigger.branches"

    const val PR_TRIGGER_ENABLED: String = "teamcity.github.bridge.prTrigger.enabled"
    const val PR_TRIGGER_BRANCHES: String = "teamcity.github.bridge.prTrigger.branches"
}

// Which ref the bridge enqueues a PR build on.
enum class PrBuildRef {
    // `pull/N` — the GitHub PR ref, mapped by the VCS root's branch spec.
    // The only option that works for PRs from forks, hence the default.
    PULL,

    // The PR's own head branch (e.g. `Feature/toto`). Readable in every
    // TeamCity screen, and there is no second ref for the same commit — so a
    // pre-PR branch build and the PR build are one build. Requires the head
    // branch to be in the VCS root's branch spec, and forks to be out of
    // scope (a fork's head ref does not exist in this repository).
    BRANCH;

    companion object {
        // Anything unrecognised (including blank) keeps the historical
        // behaviour: a project that says nothing keeps building `pull/N`.
        fun parse(raw: String?): PrBuildRef =
            if (raw?.trim().equals(BRANCH.name, ignoreCase = true)) BRANCH else PULL
    }
}

// Fully resolved configuration of the GitHub Bridge for a given BT.
// The reader walks the project chain for the mandatory keys and the
// project-level branch lists, then overlays the BT-level overrides.
data class BridgeFeatureConfig(
    val repo: RepoCoords,
    val connectionId: String,
    // Project-level trigger paths.
    val branchTriggerEnabled: Boolean,
    val prTriggerEnabled: Boolean,
    // Resolved branch lists (BT override REPLACES project when set).
    val branchTriggerBranches: BranchSpecMatcher,
    val prTriggerBranches: BranchSpecMatcher,
    // Per-BT HARD flags. `triggerOnPrDraft=true` requires
    // `triggerOnPrReady=true`; the reader enforces this by clamping
    // `effective triggerOnPrDraft = triggerOnPrDraft && triggerOnPrReady`.
    val triggerOnBranch: Boolean,
    val triggerOnPrReady: Boolean,
    val triggerOnPrDraft: Boolean,
    // Optional monorepo path filter over the PR's changed files. Empty =
    // no path restriction. Enforced by the listener only (it needs the
    // PR file list from the GitHub API). Defaults to an empty matcher so
    // existing call sites/tests need not supply it.
    val pathFilter: BranchSpecMatcher = BranchSpecMatcher.parse(null),
    // When true, this BT is enqueued on PR approval (pull_request_review),
    // for suites deliberately gated behind review. Default false.
    val runOnApproval: Boolean = false,
    // Optional PR-comment trigger phrase (case-insensitive substring).
    // Empty = no comment trigger.
    val commentTrigger: String = "",
    // PR-metadata gate (enforced for auto triggers; manual bypasses):
    //   - requirePhrase: run only if the PR title OR body contains it.
    //   - skipPhrase:    skip if the PR title OR body contains it (e.g. [skip ci]).
    //   - labelFilter:   +:/-: filter over PR label names (e.g. -:no-ci, +:ci).
    // All empty = no metadata restriction.
    val requirePhrase: String = "",
    val skipPhrase: String = "",
    val labelFilter: BranchSpecMatcher = BranchSpecMatcher.parse(null),
    // Project-level choice of the ref PR builds run on. Defaults to the
    // historical `pull/N`.
    val prBuildRef: PrBuildRef = PrBuildRef.PULL,
    // Remove an automatically-triggered build from the queue when this exact
    // commit already passed in this build configuration, and republish that
    // success. Off by default: a scheduled suite is expected to re-run on an
    // unchanged commit (that is how environment rot is caught).
    val skipIfCommitPassed: Boolean = false,
    // Does this build configuration report to GitHub at all?
    //
    // This is the ONLY thing publication depends on: it is deliberately
    // independent of what started the build. A build configuration that
    // publishes does so for a PR event, a VCS trigger, a schedule, a manual
    // Run or a GitHub command alike; one that does not publish is invisible
    // on GitHub whatever happens. Trigger gates and filters decide what
    // *runs*, not what is *reported*.
    val publishChecks: Boolean = true,
)

object BridgeFeatureReader {

    // Returns the resolved config when:
    //   - the BT has the `github-bridge` feature, AND
    //   - the surrounding project chain provides repo + connectionId.
    // null otherwise.
    //
    // Project-chain configuration parameters are read via
    // `buildType.project.parameters` — that's the SProject's
    // `InheritableUserParametersHolder.getParameters()`, which is
    // the documented inheritance path for project-chain params.
    // (`buildType.parameters` and `buildType.parametersProvider.all`
    // both proved unreliable on TC 2026.1 for inherited project
    // params — they return own + template-inherited only.)
    fun read(buildType: SBuildType): BridgeFeatureConfig? {
        // Read through `resolvedSettings`, NOT `buildType.getBuildFeaturesOfType`.
        // The latter returns only features attached DIRECTLY to the BT
        // ("features added to this settings object"), so a BT that
        // inherits the GitHub Bridge feature from a BuildType template
        // without re-attaching it locally was invisible to the plugin —
        // it never got enqueued and never got a Check Run. `resolvedSettings`
        // is the effective configuration with templates applied and
        // disabled features removed ("enabled and resolved" per the SDK),
        // which is exactly the set of features that govern this BT.
        val feature = buildType.resolvedSettings
            .getBuildFeaturesOfType(GitHubBridgeBuildFeature.FEATURE_TYPE)
            .firstOrNull() ?: return null
        return fromInputs(
            projectParams = buildType.project.parameters,
            featureParams = feature.parameters,
        )
    }

    // Pure helper exposed for unit tests: same logic as `read` but
    // operates on the two raw param maps, without an SBuildType.
    fun fromInputs(
        projectParams: Map<String, String>,
        featureParams: Map<String, String>,
    ): BridgeFeatureConfig? {
        val repoSlug = projectParams[BridgeProjectParams.REPO]
            ?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val connectionId = projectParams[BridgeProjectParams.CONNECTION_ID]
            ?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val repo = try {
            RepoCoords.parse(repoSlug)
        } catch (e: IllegalArgumentException) {
            return null
        }

        // Project-level toggles (default true).
        val branchTriggerEnabled = projectParams[BridgeProjectParams.BRANCH_TRIGGER_ENABLED] != "false"
        val prTriggerEnabled = projectParams[BridgeProjectParams.PR_TRIGGER_ENABLED] != "false"

        // Resolve branch lists: BT override REPLACES project when non-blank.
        val branchTriggerSpec = featureParams[GitHubBridgeBuildFeature.PARAM_BRANCH_TRIGGER_OVERRIDE]
            ?.takeIf { it.isNotBlank() }
            ?: projectParams[BridgeProjectParams.BRANCH_TRIGGER_BRANCHES].orEmpty()
        val prTriggerSpec = featureParams[GitHubBridgeBuildFeature.PARAM_PR_TRIGGER_OVERRIDE]
            ?.takeIf { it.isNotBlank() }
            ?: projectParams[BridgeProjectParams.PR_TRIGGER_BRANCHES].orEmpty()

        // Per-BT HARD flags (default true).
        val triggerOnBranch = featureParams[GitHubBridgeBuildFeature.PARAM_TRIGGER_ON_BRANCH] != "false"
        val triggerOnPrReady = featureParams[GitHubBridgeBuildFeature.PARAM_TRIGGER_ON_PR_READY] != "false"
        val rawTriggerOnPrDraft = featureParams[GitHubBridgeBuildFeature.PARAM_TRIGGER_ON_PR_DRAFT] != "false"
        // Tolerance: a stored state of (PrReady=OFF, PrDraft=ON) is
        // nonsensical (you can't run on drafts if you don't run on
        // ready). Treat as (OFF, OFF).
        val triggerOnPrDraft = rawTriggerOnPrDraft && triggerOnPrReady

        return BridgeFeatureConfig(
            repo = repo,
            connectionId = connectionId,
            branchTriggerEnabled = branchTriggerEnabled,
            prTriggerEnabled = prTriggerEnabled,
            branchTriggerBranches = BranchSpecMatcher.parse(branchTriggerSpec),
            prTriggerBranches = BranchSpecMatcher.parse(prTriggerSpec),
            triggerOnBranch = triggerOnBranch,
            triggerOnPrReady = triggerOnPrReady,
            triggerOnPrDraft = triggerOnPrDraft,
            pathFilter = BranchSpecMatcher.parse(featureParams[GitHubBridgeBuildFeature.PARAM_PATH_FILTER]),
            runOnApproval = featureParams[GitHubBridgeBuildFeature.PARAM_RUN_ON_APPROVAL] == "true",
            commentTrigger = featureParams[GitHubBridgeBuildFeature.PARAM_COMMENT_TRIGGER]?.trim().orEmpty(),
            requirePhrase = featureParams[GitHubBridgeBuildFeature.PARAM_REQUIRE_PHRASE]?.trim().orEmpty(),
            skipPhrase = featureParams[GitHubBridgeBuildFeature.PARAM_SKIP_PHRASE]?.trim().orEmpty(),
            labelFilter = BranchSpecMatcher.parse(featureParams[GitHubBridgeBuildFeature.PARAM_LABEL_FILTER]),
            prBuildRef = PrBuildRef.parse(projectParams[BridgeProjectParams.PR_BUILD_REF]),
            skipIfCommitPassed = featureParams[GitHubBridgeBuildFeature.PARAM_SKIP_IF_COMMIT_PASSED] == "true",
            publishChecks = featureParams[GitHubBridgeBuildFeature.PARAM_PUBLISH_CHECKS] != "false",
        )
    }
}

// Should a build launched on a plain branch ref resolve its PR from the
// built commit? True when the operator enabled the server-wide lookup, and
// implicitly in branch-source mode — there, *every* PR build is a branch
// build, so switching the lookup off would strip PR parameters, tags and
// comments from all of them.
fun BridgeFeatureConfig.resolvesPrFromCommit(serverLookupEnabled: Boolean): Boolean =
    serverLookupEnabled || prBuildRef == PrBuildRef.BRANCH

// Decision the gate produces for a single build (or potential build,
// from the listener) of an opt-in BT in a given context.
enum class GateDecision {
    // Build should run / listener should enqueue.
    ALLOW,
    // HARD block — even manual triggers cannot bypass. Silent on
    // GitHub (no Check Run).
    SUPPRESS_HARD,
    // Branch filter excluded the PR's source branch. Manual triggers
    // bypass this. From auto: post "Skipped: branch out of scope".
    SUPPRESS_BRANCH_PR,
    // Branch filter excluded a non-PR branch. Manual triggers bypass.
    // Silent on GitHub (per spec: no Skipped CR on non-PR contexts).
    SUPPRESS_BRANCH_NON_PR,
    // PR is in draft AND BT has `triggerOnPrDraft=false` (with
    // `triggerOnPrReady=true`). Manual would HARD-block instead (see
    // gate); from auto: post "Skipped: draft PR".
    SUPPRESS_DRAFT,
    // PR metadata (title/body phrase filters or label filter) excluded
    // this build. Manual triggers bypass. From auto: post
    // "Skipped: PR metadata out of scope".
    SUPPRESS_METADATA,
}

// Centralised gating logic shared by:
//   - PullRequestEventListener (decides whether to enqueue on a webhook)
//   - DraftBuildQueueCleaner (decides whether to remove a queued build)
//   - DraftAwareBuildFilter (decides whether to hold a queued build)
//   - BuildStatusCheckRunPublisher (decides whether to skip the "Queued" CR)
object BridgeGate {

    // Decide for a given (BT config, branch, optional PR info, trigger source).
    //
    // `prNumber` is what makes this a PR context: non-null means "this
    // build belongs to PR #n", whatever the ref it runs on. The caller
    // knows — from the `pull/N` ref, or (in branch-source mode) from the
    // commit → PR lookup — so the gate never parses branch names.
    // `prDraft` / `prHeadRef` are null outside a PR context.
    //
    // `trigger`:
    //   - AUTO             => every block applies.
    //   - COMMAND / MANUAL => explicit request: HARD blocks still apply,
    //                         SOFT ones (branch list, PR metadata) do not.
    fun decide(
        config: BridgeFeatureConfig,
        branchName: String,
        prNumber: Int?,
        prDraft: Boolean?,
        prHeadRef: String?,
        trigger: BridgeTrigger,
        prTitle: String = "",
        prBody: String = "",
        prLabels: List<String> = emptyList(),
    ): GateDecision =
        if (prNumber != null) decidePr(config, branchName, prDraft, prHeadRef, trigger, prTitle, prBody, prLabels)
        else decideBranch(config, branchName, trigger)

    private fun decidePr(
        config: BridgeFeatureConfig,
        branchName: String,
        prDraft: Boolean?,
        prHeadRef: String?,
        trigger: BridgeTrigger,
        prTitle: String,
        prBody: String,
        prLabels: List<String>,
    ): GateDecision {
        // Project-level kill switch: the bridge is mute for this path, for
        // every trigger. It never removes a build, it just says nothing.
        if (!config.prTriggerEnabled) return GateDecision.SUPPRESS_HARD
        // "On demand" BT: not part of the PR check set, so nothing automatic
        // — but an explicit run reports normally.
        if (!config.triggerOnPrReady) {
            return if (trigger.isExplicit) GateDecision.ALLOW else GateDecision.SUPPRESS_HARD
        }
        // Draft PR of a BT that skips drafts: suppressed on the automatic
        // path (with a "Skipped: draft PR" row), but an explicit request wins
        // — nobody should click Run and get silence.
        if (prDraft == true && !config.triggerOnPrDraft) {
            return if (trigger.isExplicit) GateDecision.ALLOW else GateDecision.SUPPRESS_DRAFT
        }
        // An explicit request bypasses the SOFT filters (branch + metadata).
        if (trigger.isExplicit) return GateDecision.ALLOW
        // Branch filter (matched against headRef for `pull/*`).
        if (!config.prTriggerBranches.matches(branchName, prHeadRef)) {
            return GateDecision.SUPPRESS_BRANCH_PR
        }
        // PR-metadata filter (title/body phrases + labels).
        if (!metadataAllows(config, prTitle, prBody, prLabels)) {
            return GateDecision.SUPPRESS_METADATA
        }
        return GateDecision.ALLOW
    }

    // True iff the PR's title/body/labels satisfy the build's metadata
    // filters. Public for unit testing.
    fun metadataAllows(
        config: BridgeFeatureConfig,
        prTitle: String,
        prBody: String,
        prLabels: List<String>,
    ): Boolean {
        val text = "$prTitle\n$prBody"
        if (config.skipPhrase.isNotBlank() && text.contains(config.skipPhrase, ignoreCase = true)) {
            return false
        }
        if (config.requirePhrase.isNotBlank() && !text.contains(config.requirePhrase, ignoreCase = true)) {
            return false
        }
        if (!config.labelFilter.isEmpty() && !config.labelFilter.matchesSet(prLabels)) {
            return false
        }
        return true
    }

    private fun decideBranch(
        config: BridgeFeatureConfig,
        branchName: String,
        trigger: BridgeTrigger,
    ): GateDecision {
        if (!config.branchTriggerEnabled) return GateDecision.SUPPRESS_HARD
        if (!config.triggerOnBranch) {
            return if (trigger.isExplicit) GateDecision.ALLOW else GateDecision.SUPPRESS_HARD
        }
        if (trigger.isExplicit) return GateDecision.ALLOW
        if (!config.branchTriggerBranches.matches(branchName)) {
            return GateDecision.SUPPRESS_BRANCH_NON_PR
        }
        return GateDecision.ALLOW
    }
}
