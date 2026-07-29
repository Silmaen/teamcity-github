package io.github.dlachouette.teamcity.github.feature

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BridgeFeatureConfigTest {

    private val mandatoryProjectParams = mapOf(
        BridgeProjectParams.REPO to "acme/widget",
        BridgeProjectParams.CONNECTION_ID to "CID_abc",
    )

    @Test
    fun `fromInputs returns config with all defaults when only mandatory project params are set`() {
        val c = BridgeFeatureReader.fromInputs(mandatoryProjectParams, emptyMap())
        assertNotNull(c)
        assertEquals("acme", c!!.repo.owner)
        assertEquals("widget", c.repo.name)
        assertEquals("CID_abc", c.connectionId)
        // Project-level toggles default to true
        assertTrue(c.branchTriggerEnabled)
        assertTrue(c.prTriggerEnabled)
        // Branch lists default to empty = match all
        assertTrue(c.branchTriggerBranches.isEmpty())
        assertTrue(c.prTriggerBranches.isEmpty())
        // BT-level HARD flags default to true
        assertTrue(c.triggerOnBranch)
        assertTrue(c.triggerOnPrReady)
        assertTrue(c.triggerOnPrDraft)
    }

    @Test
    fun `fromInputs returns null without repo`() {
        assertNull(BridgeFeatureReader.fromInputs(mapOf(BridgeProjectParams.CONNECTION_ID to "x"), emptyMap()))
    }

    @Test
    fun `fromInputs returns null without connection id`() {
        assertNull(BridgeFeatureReader.fromInputs(mapOf(BridgeProjectParams.REPO to "acme/widget"), emptyMap()))
    }

    @Test
    fun `fromInputs returns null on blank repo`() {
        assertNull(BridgeFeatureReader.fromInputs(
            mapOf(BridgeProjectParams.REPO to "   ", BridgeProjectParams.CONNECTION_ID to "x"),
            emptyMap(),
        ))
    }

    @Test
    fun `fromInputs returns null on invalid slug`() {
        assertNull(BridgeFeatureReader.fromInputs(
            mapOf(BridgeProjectParams.REPO to "not-a-slug", BridgeProjectParams.CONNECTION_ID to "x"),
            emptyMap(),
        ))
    }

    @Test
    fun `project toggles honour explicit false`() {
        val c = BridgeFeatureReader.fromInputs(
            mandatoryProjectParams + mapOf(
                BridgeProjectParams.BRANCH_TRIGGER_ENABLED to "false",
                BridgeProjectParams.PR_TRIGGER_ENABLED to "false",
            ),
            emptyMap(),
        )
        assertNotNull(c)
        assertFalse(c!!.branchTriggerEnabled)
        assertFalse(c.prTriggerEnabled)
    }

    @Test
    fun `project-level branch lists are parsed`() {
        val c = BridgeFeatureReader.fromInputs(
            mandatoryProjectParams + mapOf(
                BridgeProjectParams.BRANCH_TRIGGER_BRANCHES to "+:main\n+:Release/*",
                BridgeProjectParams.PR_TRIGGER_BRANCHES to "+:Feature/*\n-:Feature/scratch-*",
            ),
            emptyMap(),
        )
        assertNotNull(c)
        // Non-PR branch list
        assertTrue(c!!.branchTriggerBranches.matches("main"))
        assertTrue(c.branchTriggerBranches.matches("Release/2026Q2"))
        assertFalse(c.branchTriggerBranches.matches("scratch/x"))
        // PR list (against headRef)
        assertTrue(c.prTriggerBranches.matches("Feature/foo"))
        assertFalse(c.prTriggerBranches.matches("Feature/scratch-x"))
    }

    @Test
    fun `BT branch trigger override REPLACES project branch list`() {
        val c = BridgeFeatureReader.fromInputs(
            mandatoryProjectParams + mapOf(
                BridgeProjectParams.BRANCH_TRIGGER_BRANCHES to "+:main",
            ),
            mapOf(GitHubBridgeBuildFeature.PARAM_BRANCH_TRIGGER_OVERRIDE to "+:hotfix/*"),
        )
        assertNotNull(c)
        // Override wins: BT no longer matches `main`, only `hotfix/*`.
        assertFalse(c!!.branchTriggerBranches.matches("main"))
        assertTrue(c.branchTriggerBranches.matches("hotfix/123"))
    }

    @Test
    fun `BT PR trigger override REPLACES project PR list`() {
        val c = BridgeFeatureReader.fromInputs(
            mandatoryProjectParams + mapOf(
                BridgeProjectParams.PR_TRIGGER_BRANCHES to "+:Feature/*",
            ),
            mapOf(GitHubBridgeBuildFeature.PARAM_PR_TRIGGER_OVERRIDE to "+:Release/*\n-:Release/scratch-*"),
        )
        assertNotNull(c)
        assertFalse(c!!.prTriggerBranches.matches("Feature/foo"))
        assertTrue(c.prTriggerBranches.matches("Release/2026Q2"))
        assertFalse(c.prTriggerBranches.matches("Release/scratch-x"))
    }

    @Test
    fun `blank BT override falls back to project list`() {
        val c = BridgeFeatureReader.fromInputs(
            mandatoryProjectParams + mapOf(
                BridgeProjectParams.PR_TRIGGER_BRANCHES to "+:Feature/*",
            ),
            mapOf(GitHubBridgeBuildFeature.PARAM_PR_TRIGGER_OVERRIDE to "  \n  "),
        )
        assertNotNull(c)
        assertTrue(c!!.prTriggerBranches.matches("Feature/foo"))
    }

    @Test
    fun `BT trigger flags honour explicit false`() {
        val c = BridgeFeatureReader.fromInputs(
            mandatoryProjectParams,
            mapOf(
                GitHubBridgeBuildFeature.PARAM_TRIGGER_ON_BRANCH to "false",
                GitHubBridgeBuildFeature.PARAM_TRIGGER_ON_PR_READY to "false",
                GitHubBridgeBuildFeature.PARAM_TRIGGER_ON_PR_DRAFT to "false",
            ),
        )
        assertNotNull(c)
        assertFalse(c!!.triggerOnBranch)
        assertFalse(c.triggerOnPrReady)
        assertFalse(c.triggerOnPrDraft)
    }

    @Test
    fun `triggerOnPrDraft tolerance clamps draft to false when ready is false`() {
        // Stored state (Ready=OFF, Draft=ON) is nonsensical; reader
        // clamps Draft to OFF to keep the runtime sane.
        val c = BridgeFeatureReader.fromInputs(
            mandatoryProjectParams,
            mapOf(
                GitHubBridgeBuildFeature.PARAM_TRIGGER_ON_PR_READY to "false",
                GitHubBridgeBuildFeature.PARAM_TRIGGER_ON_PR_DRAFT to "true",
            ),
        )
        assertNotNull(c)
        assertFalse(c!!.triggerOnPrReady)
        assertFalse(c.triggerOnPrDraft)  // clamped
    }

    // --- publication and reuse flags (1.8.3) ---

    @Test
    fun `publication is on unless the feature says otherwise`() {
        assertEquals(true, BridgeFeatureReader.fromInputs(mandatoryProjectParams, emptyMap())!!.publishChecks)
        assertEquals(true, BridgeFeatureReader.fromInputs(mandatoryProjectParams, mapOf("publishChecks" to "true"))!!.publishChecks)
        assertEquals(false, BridgeFeatureReader.fromInputs(mandatoryProjectParams, mapOf("publishChecks" to "false"))!!.publishChecks)
    }

    @Test
    fun `reusing a passed commit is opt-in`() {
        assertEquals(false, BridgeFeatureReader.fromInputs(mandatoryProjectParams, emptyMap())!!.skipIfCommitPassed)
        assertEquals(false, BridgeFeatureReader.fromInputs(mandatoryProjectParams, mapOf("skipIfCommitPassed" to "false"))!!.skipIfCommitPassed)
        assertEquals(true, BridgeFeatureReader.fromInputs(mandatoryProjectParams, mapOf("skipIfCommitPassed" to "true"))!!.skipIfCommitPassed)
    }
}
