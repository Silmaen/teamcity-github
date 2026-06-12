package io.github.dlachouette.teamcity.github.feature

import jetbrains.buildServer.serverSide.BuildFeature
import jetbrains.buildServer.serverSide.InvalidProperty
import jetbrains.buildServer.serverSide.PropertiesProcessor
import jetbrains.buildServer.web.openapi.PluginDescriptor

// Build Feature: "GitHub Bridge integration".
//
// One per BuildType. Presence of the feature is THE per-task opt-in.
// Mandatory config (repo, connectionId, two trigger-path toggles +
// their branch lists) lives at the project level — see
// `BridgeProjectParams`. The feature exposes five per-task fields:
//
//   - triggerOnBranch    (HARD, default true) — does this BT run on
//                                                non-PR branches?
//   - triggerOnPrReady   (HARD, default true) — does this BT run on
//                                                ready PRs?
//   - triggerOnPrDraft   (HARD, default true) — does this BT also run
//                                                on draft PRs?
//                                                Requires triggerOnPrReady=true.
//   - branchTriggerBranchesOverride            — REPLACES the project's
//                                                non-PR branch list when set.
//   - prTriggerBranchesOverride                — REPLACES the project's
//                                                PR source-branch list when set.
//
// "HARD" means the flag is enforced for every trigger source —
// including manual operator runs. "SOFT" (branch lists) means
// manual triggers bypass the constraint.
class GitHubBridgeBuildFeature(
    private val pluginDescriptor: PluginDescriptor,
) : BuildFeature() {

    override fun getType(): String = FEATURE_TYPE

    override fun getDisplayName(): String = "GitHub Bridge integration"

    override fun getEditParametersUrl(): String =
        pluginDescriptor.getPluginResourcesPath("feature/bridgeFeatureEdit.jsp")

    override fun isMultipleFeaturesPerBuildTypeAllowed(): Boolean = false

    override fun getDefaultParameters(): Map<String, String> = mapOf(
        PARAM_TRIGGER_ON_BRANCH to "true",
        PARAM_TRIGGER_ON_PR_READY to "true",
        PARAM_TRIGGER_ON_PR_DRAFT to "true",
    )

    override fun describeParameters(params: Map<String, String>): String {
        val flags = mutableListOf<String>()
        if (params[PARAM_TRIGGER_ON_BRANCH] != "false") flags += "branches"
        if (params[PARAM_TRIGGER_ON_PR_READY] != "false") {
            if (params[PARAM_TRIGGER_ON_PR_DRAFT] != "false") flags += "PR (ready + draft)"
            else flags += "PR (ready only)"
        }
        val triggers = if (flags.isEmpty()) "manual only" else flags.joinToString(" + ")
        val overrides = mutableListOf<String>()
        if (!params[PARAM_BRANCH_TRIGGER_OVERRIDE].isNullOrBlank()) overrides += "branch list override"
        if (!params[PARAM_PR_TRIGGER_OVERRIDE].isNullOrBlank()) overrides += "PR list override"
        return if (overrides.isEmpty()) "triggers: $triggers" else "triggers: $triggers; ${overrides.joinToString(", ")}"
    }

    override fun getParametersProcessor(): PropertiesProcessor = PropertiesProcessor { input ->
        val invalid = mutableListOf<InvalidProperty>()

        // Constraint: triggerOnPrDraft=true requires triggerOnPrReady=true.
        // (You cannot run on drafts if you don't run on ready.)
        val ready = input[PARAM_TRIGGER_ON_PR_READY] != "false"
        val draft = input[PARAM_TRIGGER_ON_PR_DRAFT] != "false"
        if (draft && !ready) {
            invalid += InvalidProperty(
                PARAM_TRIGGER_ON_PR_DRAFT,
                "Cannot trigger on draft PRs while triggerOnPrReady is unchecked. " +
                    "Uncheck Draft as well, or re-check Ready.",
            )
        }

        BranchSpecMatcher.validate(input[PARAM_BRANCH_TRIGGER_OVERRIDE])?.let {
            invalid += InvalidProperty(PARAM_BRANCH_TRIGGER_OVERRIDE, it)
        }
        BranchSpecMatcher.validate(input[PARAM_PR_TRIGGER_OVERRIDE])?.let {
            invalid += InvalidProperty(PARAM_PR_TRIGGER_OVERRIDE, it)
        }
        BranchSpecMatcher.validate(input[PARAM_PATH_FILTER])?.let {
            invalid += InvalidProperty(PARAM_PATH_FILTER, it)
        }

        invalid
    }

    companion object {
        // Stable identifier referenced by both Spring and listener
        // code; changing it would orphan every BT that opted in.
        const val FEATURE_TYPE: String = "github-bridge"

        const val PARAM_TRIGGER_ON_BRANCH: String = "triggerOnBranch"
        const val PARAM_TRIGGER_ON_PR_READY: String = "triggerOnPrReady"
        const val PARAM_TRIGGER_ON_PR_DRAFT: String = "triggerOnPrDraft"
        const val PARAM_BRANCH_TRIGGER_OVERRIDE: String = "branchTriggerBranchesOverride"
        const val PARAM_PR_TRIGGER_OVERRIDE: String = "prTriggerBranchesOverride"

        // Optional monorepo path filter (VCS-filter syntax over changed
        // file paths). When non-empty, the listener only enqueues this BT
        // for a PR if the PR's changed files match the filter.
        const val PARAM_PATH_FILTER: String = "pathFilter"

        // When checked, the BT is enqueued on PR approval
        // (pull_request_review submitted=approved).
        const val PARAM_RUN_ON_APPROVAL: String = "runOnApproval"

        // Optional trigger phrase: when a PR comment contains it (and the
        // commenter is trusted), this BT is enqueued. Empty = disabled.
        const val PARAM_COMMENT_TRIGGER: String = "commentTrigger"
    }
}
