package io.github.dlachouette.teamcity.github.feature

import io.github.dlachouette.teamcity.github.api.RepoCoords
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BridgeGateTest {

    private val auto = BridgeTrigger.AUTO
    private val manual = BridgeTrigger.MANUAL
    private val command = BridgeTrigger.COMMAND

    private fun config(
        branchTriggerEnabled: Boolean = true,
        prTriggerEnabled: Boolean = true,
        branchTriggerSpec: String = "",
        prTriggerSpec: String = "",
        triggerOnBranch: Boolean = true,
        triggerOnPrReady: Boolean = true,
        triggerOnPrDraft: Boolean = true,
        requirePhrase: String = "",
        skipPhrase: String = "",
        labelSpec: String = "",
        prBuildRef: PrBuildRef = PrBuildRef.PULL,
    ) = BridgeFeatureConfig(
        repo = RepoCoords.parse("acme/widget"),
        connectionId = "CID_abc",
        branchTriggerEnabled = branchTriggerEnabled,
        prTriggerEnabled = prTriggerEnabled,
        branchTriggerBranches = BranchSpecMatcher.parse(branchTriggerSpec),
        prTriggerBranches = BranchSpecMatcher.parse(prTriggerSpec),
        triggerOnBranch = triggerOnBranch,
        triggerOnPrReady = triggerOnPrReady,
        triggerOnPrDraft = triggerOnPrDraft && triggerOnPrReady,
        requirePhrase = requirePhrase,
        skipPhrase = skipPhrase,
        labelFilter = BranchSpecMatcher.parse(labelSpec),
        prBuildRef = prBuildRef,
    )

    // --- Non-PR branch context ---

    @Test
    fun `non-PR branch allowed when triggers and branch list match`() {
        assertEquals(
            GateDecision.ALLOW,
            BridgeGate.decide(config(), branchName = "main", prNumber = null, prDraft = null, prHeadRef = null, trigger = auto),
        )
    }

    @Test
    fun `non-PR branch HARD-blocked when project disables branch trigger`() {
        // Even manual is blocked.
        BridgeTrigger.entries.forEach { manual ->
            assertEquals(
                GateDecision.SUPPRESS_HARD,
                BridgeGate.decide(config(branchTriggerEnabled = false), "main", null, null, null, manual),
            )
        }
    }

    @Test
    fun `non-PR branch HARD-blocked when BT triggerOnBranch is false`() {
        BridgeTrigger.entries.forEach { manual ->
            assertEquals(
                GateDecision.SUPPRESS_HARD,
                BridgeGate.decide(config(triggerOnBranch = false), "main", null, null, null, manual),
            )
        }
    }

    @Test
    fun `non-PR branch out of list is suppressed silently on auto`() {
        assertEquals(
            GateDecision.SUPPRESS_BRANCH_NON_PR,
            BridgeGate.decide(config(branchTriggerSpec = "+:main"), "Release/2026Q2", null, null, null, auto),
        )
    }

    @Test
    fun `non-PR branch out of list passes for manual trigger`() {
        assertEquals(
            GateDecision.ALLOW,
            BridgeGate.decide(config(branchTriggerSpec = "+:main"), "Release/2026Q2", null, null, null, manual),
        )
    }

    // --- PR-ready context ---

    @Test
    fun `PR-ready allowed when triggers and branch list match`() {
        assertEquals(
            GateDecision.ALLOW,
            BridgeGate.decide(config(), "pull/42", prNumber = 42, prDraft = false, prHeadRef = "Feature/foo", trigger = auto),
        )
    }

    @Test
    fun `PR-ready HARD-blocked when project disables PR trigger`() {
        BridgeTrigger.entries.forEach { manual ->
            assertEquals(
                GateDecision.SUPPRESS_HARD,
                BridgeGate.decide(config(prTriggerEnabled = false), "pull/42", 42, false, "Feature/foo", manual),
            )
        }
    }

    @Test
    fun `PR-ready HARD-blocked when BT triggerOnPrReady is false`() {
        BridgeTrigger.entries.forEach { manual ->
            assertEquals(
                GateDecision.SUPPRESS_HARD,
                BridgeGate.decide(config(triggerOnPrReady = false), "pull/42", 42, false, "Feature/foo", manual),
            )
        }
    }

    @Test
    fun `PR-ready out of list yields Skipped branch on auto`() {
        assertEquals(
            GateDecision.SUPPRESS_BRANCH_PR,
            BridgeGate.decide(
                config(prTriggerSpec = "+:Feature/*"),
                "pull/42", 42, false, "Release/2026Q2", auto,
            ),
        )
    }

    @Test
    fun `PR-ready out of list passes for manual`() {
        assertEquals(
            GateDecision.ALLOW,
            BridgeGate.decide(
                config(prTriggerSpec = "+:Feature/*"),
                "pull/42", 42, false, "Release/2026Q2", manual,
            ),
        )
    }

    // --- PR-draft context ---

    @Test
    fun `PR-draft allowed when triggerOnPrDraft is true and branch matches`() {
        assertEquals(
            GateDecision.ALLOW,
            BridgeGate.decide(config(), "pull/42", prNumber = 42, prDraft = true, prHeadRef = "Feature/foo", trigger = auto),
        )
    }

    @Test
    fun `PR-draft yields Skipped draft on auto when triggerOnPrDraft is false`() {
        assertEquals(
            GateDecision.SUPPRESS_DRAFT,
            BridgeGate.decide(
                config(triggerOnPrDraft = false),
                "pull/42", 42, true, "Feature/foo", auto,
            ),
        )
    }

    @Test
    fun `PR-draft HARD-blocks manual trigger when triggerOnPrDraft is false`() {
        // Manual on a draft PR for a BT that excludes drafts is blocked.
        assertEquals(
            GateDecision.SUPPRESS_HARD,
            BridgeGate.decide(
                config(triggerOnPrDraft = false),
                "pull/42", 42, true, "Feature/foo", manual,
            ),
        )
    }

    @Test
    fun `PR-draft on a BT that does not run on PRs at all is HARD-blocked`() {
        BridgeTrigger.entries.forEach { manual ->
            // triggerOnPrReady=false => the gate clamps Draft to false
            // via the config builder; the BT is "not for PRs at all"
            // (HARD block for both ready and draft PRs).
            val cfg = config(triggerOnPrReady = false, triggerOnPrDraft = false)
            assertEquals(
                GateDecision.SUPPRESS_HARD,
                BridgeGate.decide(cfg, "pull/42", 42, true, "Feature/foo", manual),
            )
        }
    }

    // --- PR metadata gate (title / body / labels) ---

    @Test
    fun `skip phrase in title suppresses (auto) but manual bypasses`() {
        val cfg = config(skipPhrase = "[skip ci]")
        assertEquals(
            GateDecision.SUPPRESS_METADATA,
            BridgeGate.decide(cfg, "pull/7", 7, false, "feature/x", auto, prTitle = "Fix bug [skip ci]"),
        )
        assertEquals(
            GateDecision.ALLOW,
            BridgeGate.decide(cfg, "pull/7", 7, false, "feature/x", manual, prTitle = "Fix bug [skip ci]"),
        )
        assertEquals(
            GateDecision.ALLOW,
            BridgeGate.decide(cfg, "pull/7", 7, false, "feature/x", auto, prTitle = "Fix bug"),
        )
    }

    @Test
    fun `require phrase must appear in title or body`() {
        val cfg = config(requirePhrase = "/fulltest")
        assertEquals(
            GateDecision.ALLOW,
            BridgeGate.decide(cfg, "pull/7", 7, false, "feature/x", auto, prTitle = "t", prBody = "please /fulltest"),
        )
        assertEquals(
            GateDecision.SUPPRESS_METADATA,
            BridgeGate.decide(cfg, "pull/7", 7, false, "feature/x", auto, prTitle = "t", prBody = "nothing here"),
        )
    }

    @Test
    fun `label filter include and exclude`() {
        val include = config(labelSpec = "+:ci")
        assertEquals(
            GateDecision.ALLOW,
            BridgeGate.decide(include, "pull/7", 7, false, "feature/x", auto, prLabels = listOf("ci", "bug")),
        )
        assertEquals(
            GateDecision.SUPPRESS_METADATA,
            BridgeGate.decide(include, "pull/7", 7, false, "feature/x", auto, prLabels = listOf("bug")),
        )

        val exclude = config(labelSpec = "-:no-ci")
        assertEquals(
            GateDecision.SUPPRESS_METADATA,
            BridgeGate.decide(exclude, "pull/7", 7, false, "feature/x", auto, prLabels = listOf("no-ci")),
        )
        assertEquals(
            GateDecision.ALLOW,
            BridgeGate.decide(exclude, "pull/7", 7, false, "feature/x", auto, prLabels = listOf("ready")),
        )
    }

    @Test
    fun `no metadata filters means allow`() {
        assertEquals(
            GateDecision.ALLOW,
            BridgeGate.decide(config(), "pull/7", 7, false, "feature/x", auto, prTitle = "anything", prLabels = listOf("x")),
        )
    }

    // --- Explicit commands (G11): a comment / approval / re-run / API
    // trigger must not be undone by the filters that keep the automatic
    // path narrow. Same treatment as a manual Run.

    @Test
    fun `a command bypasses the PR branch filter`() {
        val cfg = config(prTriggerSpec = "+:Release/*")
        assertEquals(
            GateDecision.SUPPRESS_BRANCH_PR,
            BridgeGate.decide(cfg, "pull/42", 42, false, "Feature/foo", auto),
        )
        assertEquals(
            GateDecision.ALLOW,
            BridgeGate.decide(cfg, "pull/42", 42, false, "Feature/foo", command),
        )
    }

    @Test
    fun `a command bypasses the metadata filters`() {
        val skip = config(skipPhrase = "[skip ci]")
        assertEquals(
            GateDecision.SUPPRESS_METADATA,
            BridgeGate.decide(skip, "pull/7", 7, false, "feature/x", auto, prTitle = "Fix typo [skip ci]"),
        )
        assertEquals(
            GateDecision.ALLOW,
            BridgeGate.decide(skip, "pull/7", 7, false, "feature/x", command, prTitle = "Fix typo [skip ci]"),
        )

        // The label filter is the shape used to keep an on-demand suite off
        // the automatic path; a command must still get through.
        val labelled = config(labelSpec = "+:ci-full")
        assertEquals(
            GateDecision.SUPPRESS_METADATA,
            BridgeGate.decide(labelled, "pull/7", 7, false, "feature/x", auto, prLabels = emptyList()),
        )
        assertEquals(
            GateDecision.ALLOW,
            BridgeGate.decide(labelled, "pull/7", 7, false, "feature/x", command, prLabels = emptyList()),
        )
    }

    @Test
    fun `a command bypasses the non-PR branch filter`() {
        val cfg = config(branchTriggerSpec = "+:main")
        assertEquals(
            GateDecision.SUPPRESS_BRANCH_NON_PR,
            BridgeGate.decide(cfg, "Experiment/spike", null, null, null, auto),
        )
        assertEquals(
            GateDecision.ALLOW,
            BridgeGate.decide(cfg, "Experiment/spike", null, null, null, command),
        )
    }

    @Test
    fun `a command cannot override a HARD block`() {
        listOf(
            config(prTriggerEnabled = false),
            config(triggerOnPrReady = false),
        ).forEach { cfg ->
            assertEquals(
                GateDecision.SUPPRESS_HARD,
                BridgeGate.decide(cfg, "pull/42", 42, false, "Feature/foo", command),
            )
        }
        assertEquals(
            GateDecision.SUPPRESS_HARD,
            BridgeGate.decide(config(triggerOnBranch = false), "main", null, null, null, command),
        )
    }

    @Test
    fun `a command on a draft PR is silent when the BT skips drafts`() {
        val cfg = config(triggerOnPrDraft = false)
        // Automatic: the operator gets a "Skipped: draft PR" row.
        assertEquals(
            GateDecision.SUPPRESS_DRAFT,
            BridgeGate.decide(cfg, "pull/42", 42, true, "Feature/foo", auto),
        )
        // Explicit: the build still does not run (the BT declared it does
        // not build drafts) but no unsolicited row is posted.
        assertEquals(
            GateDecision.SUPPRESS_HARD,
            BridgeGate.decide(cfg, "pull/42", 42, true, "Feature/foo", command),
        )
    }

    // --- PR context comes from the PR number, not from the ref name (G18)

    @Test
    fun `a build on the head ref is gated as a PR when the PR number is known`() {
        val cfg = config(triggerOnPrDraft = false, prBuildRef = PrBuildRef.BRANCH)
        // Branch-source mode: the ref is `Feature/foo`, yet the draft rule
        // of the PR path applies because the caller resolved PR #42.
        assertEquals(
            GateDecision.SUPPRESS_DRAFT,
            BridgeGate.decide(cfg, "Feature/foo", 42, true, "Feature/foo", auto),
        )
        // Same ref, no PR resolved: plain branch rules, drafts irrelevant.
        assertEquals(
            GateDecision.ALLOW,
            BridgeGate.decide(cfg, "Feature/foo", null, null, null, auto),
        )
    }

    @Test
    fun `the PR branch filter matches the head ref in branch-source mode`() {
        val cfg = config(prTriggerSpec = "+:Release/*", prBuildRef = PrBuildRef.BRANCH)
        assertEquals(
            GateDecision.SUPPRESS_BRANCH_PR,
            BridgeGate.decide(cfg, "Feature/foo", 42, false, "Feature/foo", auto),
        )
        assertEquals(
            GateDecision.ALLOW,
            BridgeGate.decide(cfg, "Release/26.06", 42, false, "Release/26.06", auto),
        )
    }
}
