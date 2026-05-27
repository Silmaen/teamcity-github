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
// list = match every branch on that path.
object BridgeProjectParams {
    const val REPO: String = "teamcity.github.bridge.repo"
    const val CONNECTION_ID: String = "teamcity.github.bridge.connectionId"

    const val BRANCH_TRIGGER_ENABLED: String = "teamcity.github.bridge.branchTrigger.enabled"
    const val BRANCH_TRIGGER_BRANCHES: String = "teamcity.github.bridge.branchTrigger.branches"

    const val PR_TRIGGER_ENABLED: String = "teamcity.github.bridge.prTrigger.enabled"
    const val PR_TRIGGER_BRANCHES: String = "teamcity.github.bridge.prTrigger.branches"
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
        val feature = buildType.getBuildFeaturesOfType(GitHubBridgeBuildFeature.FEATURE_TYPE)
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
        )
    }
}

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
}

// Centralised gating logic shared by:
//   - PullRequestEventListener (decides whether to enqueue on a webhook)
//   - DraftBuildQueueCleaner (decides whether to remove a queued build)
//   - DraftAwareBuildFilter (decides whether to hold a queued build)
//   - BuildStatusCheckRunPublisher (decides whether to skip the "Queued" CR)
object BridgeGate {

    // Decide for a given (BT config, branch, optional PR info, trigger source).
    //
    // `prDraft` and `prHeadRef` are null for non-PR contexts. For PR
    // builds, the branch name starts with `pull/N` and the headRef
    // is the PR's source branch.
    //
    // `isManualTrigger`:
    //   - true  => operator clicked Run in the TC UI. HARD blocks
    //              still apply; SOFT (branch lists) are bypassed.
    //   - false => any non-manual path (VCS trigger, listener, etc.).
    //              All blocks apply.
    fun decide(
        config: BridgeFeatureConfig,
        branchName: String,
        prDraft: Boolean?,
        prHeadRef: String?,
        isManualTrigger: Boolean,
    ): GateDecision {
        val isPr = branchName.startsWith("pull/")
        return if (isPr) decidePr(config, branchName, prDraft, prHeadRef, isManualTrigger)
        else decideBranch(config, branchName, isManualTrigger)
    }

    private fun decidePr(
        config: BridgeFeatureConfig,
        branchName: String,
        prDraft: Boolean?,
        prHeadRef: String?,
        isManualTrigger: Boolean,
    ): GateDecision {
        // Project-level kill switch.
        if (!config.prTriggerEnabled) return GateDecision.SUPPRESS_HARD
        // BT not for PRs at all. HARD: applies to manual too.
        if (!config.triggerOnPrReady) return GateDecision.SUPPRESS_HARD
        // Draft state but BT skips drafts. HARD if manual; otherwise
        // SOFT-like SUPPRESS_DRAFT (post Skipped CR).
        if (prDraft == true && !config.triggerOnPrDraft) {
            return if (isManualTrigger) GateDecision.SUPPRESS_HARD else GateDecision.SUPPRESS_DRAFT
        }
        // Manual bypasses branch filter.
        if (isManualTrigger) return GateDecision.ALLOW
        // Branch filter (matched against headRef for `pull/*`).
        if (!config.prTriggerBranches.matches(branchName, prHeadRef)) {
            return GateDecision.SUPPRESS_BRANCH_PR
        }
        return GateDecision.ALLOW
    }

    private fun decideBranch(
        config: BridgeFeatureConfig,
        branchName: String,
        isManualTrigger: Boolean,
    ): GateDecision {
        if (!config.branchTriggerEnabled) return GateDecision.SUPPRESS_HARD
        if (!config.triggerOnBranch) return GateDecision.SUPPRESS_HARD
        if (isManualTrigger) return GateDecision.ALLOW
        if (!config.branchTriggerBranches.matches(branchName)) {
            return GateDecision.SUPPRESS_BRANCH_NON_PR
        }
        return GateDecision.ALLOW
    }
}
