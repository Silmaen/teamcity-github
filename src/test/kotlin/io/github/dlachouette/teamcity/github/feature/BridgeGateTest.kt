package io.github.dlachouette.teamcity.github.feature

import io.github.dlachouette.teamcity.github.api.RepoCoords
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BridgeGateTest {

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
    )

    // --- Non-PR branch context ---

    @Test
    fun `non-PR branch allowed when triggers and branch list match`() {
        assertEquals(
            GateDecision.ALLOW,
            BridgeGate.decide(config(), branchName = "main", prDraft = null, prHeadRef = null, isManualTrigger = false),
        )
    }

    @Test
    fun `non-PR branch HARD-blocked when project disables branch trigger`() {
        // Even manual is blocked.
        listOf(false, true).forEach { manual ->
            assertEquals(
                GateDecision.SUPPRESS_HARD,
                BridgeGate.decide(config(branchTriggerEnabled = false), "main", null, null, manual),
            )
        }
    }

    @Test
    fun `non-PR branch HARD-blocked when BT triggerOnBranch is false`() {
        listOf(false, true).forEach { manual ->
            assertEquals(
                GateDecision.SUPPRESS_HARD,
                BridgeGate.decide(config(triggerOnBranch = false), "main", null, null, manual),
            )
        }
    }

    @Test
    fun `non-PR branch out of list is suppressed silently on auto`() {
        assertEquals(
            GateDecision.SUPPRESS_BRANCH_NON_PR,
            BridgeGate.decide(config(branchTriggerSpec = "+:main"), "Release/2026Q2", null, null, isManualTrigger = false),
        )
    }

    @Test
    fun `non-PR branch out of list passes for manual trigger`() {
        assertEquals(
            GateDecision.ALLOW,
            BridgeGate.decide(config(branchTriggerSpec = "+:main"), "Release/2026Q2", null, null, isManualTrigger = true),
        )
    }

    // --- PR-ready context ---

    @Test
    fun `PR-ready allowed when triggers and branch list match`() {
        assertEquals(
            GateDecision.ALLOW,
            BridgeGate.decide(config(), "pull/42", prDraft = false, prHeadRef = "Feature/foo", isManualTrigger = false),
        )
    }

    @Test
    fun `PR-ready HARD-blocked when project disables PR trigger`() {
        listOf(false, true).forEach { manual ->
            assertEquals(
                GateDecision.SUPPRESS_HARD,
                BridgeGate.decide(config(prTriggerEnabled = false), "pull/42", false, "Feature/foo", manual),
            )
        }
    }

    @Test
    fun `PR-ready HARD-blocked when BT triggerOnPrReady is false`() {
        listOf(false, true).forEach { manual ->
            assertEquals(
                GateDecision.SUPPRESS_HARD,
                BridgeGate.decide(config(triggerOnPrReady = false), "pull/42", false, "Feature/foo", manual),
            )
        }
    }

    @Test
    fun `PR-ready out of list yields Skipped branch on auto`() {
        assertEquals(
            GateDecision.SUPPRESS_BRANCH_PR,
            BridgeGate.decide(
                config(prTriggerSpec = "+:Feature/*"),
                "pull/42", false, "Release/2026Q2", isManualTrigger = false,
            ),
        )
    }

    @Test
    fun `PR-ready out of list passes for manual`() {
        assertEquals(
            GateDecision.ALLOW,
            BridgeGate.decide(
                config(prTriggerSpec = "+:Feature/*"),
                "pull/42", false, "Release/2026Q2", isManualTrigger = true,
            ),
        )
    }

    // --- PR-draft context ---

    @Test
    fun `PR-draft allowed when triggerOnPrDraft is true and branch matches`() {
        assertEquals(
            GateDecision.ALLOW,
            BridgeGate.decide(config(), "pull/42", prDraft = true, prHeadRef = "Feature/foo", isManualTrigger = false),
        )
    }

    @Test
    fun `PR-draft yields Skipped draft on auto when triggerOnPrDraft is false`() {
        assertEquals(
            GateDecision.SUPPRESS_DRAFT,
            BridgeGate.decide(
                config(triggerOnPrDraft = false),
                "pull/42", true, "Feature/foo", isManualTrigger = false,
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
                "pull/42", true, "Feature/foo", isManualTrigger = true,
            ),
        )
    }

    @Test
    fun `PR-draft on a BT that does not run on PRs at all is HARD-blocked`() {
        listOf(false, true).forEach { manual ->
            // triggerOnPrReady=false => the gate clamps Draft to false
            // via the config builder; the BT is "not for PRs at all"
            // (HARD block for both ready and draft PRs).
            val cfg = config(triggerOnPrReady = false, triggerOnPrDraft = false)
            assertEquals(
                GateDecision.SUPPRESS_HARD,
                BridgeGate.decide(cfg, "pull/42", true, "Feature/foo", manual),
            )
        }
    }

    // --- PR metadata gate (title / body / labels) ---

    @Test
    fun `skip phrase in title suppresses (auto) but manual bypasses`() {
        val cfg = config(skipPhrase = "[skip ci]")
        assertEquals(
            GateDecision.SUPPRESS_METADATA,
            BridgeGate.decide(cfg, "pull/7", false, "feature/x", isManualTrigger = false, prTitle = "Fix bug [skip ci]"),
        )
        assertEquals(
            GateDecision.ALLOW,
            BridgeGate.decide(cfg, "pull/7", false, "feature/x", isManualTrigger = true, prTitle = "Fix bug [skip ci]"),
        )
        assertEquals(
            GateDecision.ALLOW,
            BridgeGate.decide(cfg, "pull/7", false, "feature/x", isManualTrigger = false, prTitle = "Fix bug"),
        )
    }

    @Test
    fun `require phrase must appear in title or body`() {
        val cfg = config(requirePhrase = "/fulltest")
        assertEquals(
            GateDecision.ALLOW,
            BridgeGate.decide(cfg, "pull/7", false, "feature/x", false, prTitle = "t", prBody = "please /fulltest"),
        )
        assertEquals(
            GateDecision.SUPPRESS_METADATA,
            BridgeGate.decide(cfg, "pull/7", false, "feature/x", false, prTitle = "t", prBody = "nothing here"),
        )
    }

    @Test
    fun `label filter include and exclude`() {
        val include = config(labelSpec = "+:ci")
        assertEquals(
            GateDecision.ALLOW,
            BridgeGate.decide(include, "pull/7", false, "feature/x", false, prLabels = listOf("ci", "bug")),
        )
        assertEquals(
            GateDecision.SUPPRESS_METADATA,
            BridgeGate.decide(include, "pull/7", false, "feature/x", false, prLabels = listOf("bug")),
        )

        val exclude = config(labelSpec = "-:no-ci")
        assertEquals(
            GateDecision.SUPPRESS_METADATA,
            BridgeGate.decide(exclude, "pull/7", false, "feature/x", false, prLabels = listOf("no-ci")),
        )
        assertEquals(
            GateDecision.ALLOW,
            BridgeGate.decide(exclude, "pull/7", false, "feature/x", false, prLabels = listOf("ready")),
        )
    }

    @Test
    fun `no metadata filters means allow`() {
        assertEquals(
            GateDecision.ALLOW,
            BridgeGate.decide(config(), "pull/7", false, "feature/x", false, prTitle = "anything", prLabels = listOf("x")),
        )
    }
}
