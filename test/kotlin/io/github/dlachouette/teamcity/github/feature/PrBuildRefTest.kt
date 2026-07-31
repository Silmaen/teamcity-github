package io.github.dlachouette.teamcity.github.feature

import io.github.dlachouette.teamcity.github.api.RepoCoords
import io.github.dlachouette.teamcity.github.web.PullRequestEventListener
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// G18: which ref a PR build runs on, and what that implies for the
// commit -> PR lookup.
class PrBuildRefTest {

    private fun config(prBuildRef: PrBuildRef) = BridgeFeatureConfig(
        repo = RepoCoords.parse("acme/widget"),
        connectionId = "managed",
        branchTriggerEnabled = true,
        prTriggerEnabled = true,
        branchTriggerBranches = BranchSpecMatcher.parse(""),
        prTriggerBranches = BranchSpecMatcher.parse(""),
        triggerOnBranch = true,
        triggerOnPrReady = true,
        triggerOnPrDraft = true,
        prBuildRef = prBuildRef,
    )

    @Test
    fun `the project parameter drives the mode`() {
        assertEquals(
            PrBuildRef.BRANCH,
            BridgeFeatureReader.fromInputs(
                projectParams = mapOf(
                    BridgeProjectParams.REPO to "acme/widget",
                    BridgeProjectParams.CONNECTION_ID to "managed",
                    BridgeProjectParams.PR_BUILD_REF to "branch",
                ),
                featureParams = emptyMap(),
            )!!.prBuildRef,
        )
    }

    @Test
    fun `a project that says nothing keeps building pull refs`() {
        assertEquals(
            PrBuildRef.PULL,
            BridgeFeatureReader.fromInputs(
                projectParams = mapOf(
                    BridgeProjectParams.REPO to "acme/widget",
                    BridgeProjectParams.CONNECTION_ID to "managed",
                ),
                featureParams = emptyMap(),
            )!!.prBuildRef,
        )
    }

    @Test
    fun `branch-source implies the commit lookup even when the server flag is off`() {
        assertTrue(config(PrBuildRef.BRANCH).resolvesPrFromCommit(serverLookupEnabled = false))
        assertTrue(config(PrBuildRef.BRANCH).resolvesPrFromCommit(serverLookupEnabled = true))
    }

    @Test
    fun `pull-ref projects follow the server flag`() {
        assertFalse(config(PrBuildRef.PULL).resolvesPrFromCommit(serverLookupEnabled = false))
        assertTrue(config(PrBuildRef.PULL).resolvesPrFromCommit(serverLookupEnabled = true))
    }
}

// The ref the listener enqueues on, per mode. Pure helper on the listener's
// companion so it is testable without the TeamCity SDK.
class PrBuildRefChoiceTest {

    private fun config(prBuildRef: PrBuildRef) = BridgeFeatureConfig(
        repo = RepoCoords.parse("acme/widget"),
        connectionId = "managed",
        branchTriggerEnabled = true,
        prTriggerEnabled = true,
        branchTriggerBranches = BranchSpecMatcher.parse(""),
        prTriggerBranches = BranchSpecMatcher.parse(""),
        triggerOnBranch = true,
        triggerOnPrReady = true,
        triggerOnPrDraft = true,
        prBuildRef = prBuildRef,
    )

    @Test
    fun `pull mode always builds the pull ref`() {
        assertEquals(
            "pull/189",
            PullRequestEventListener.prBuildRefFor(config(PrBuildRef.PULL), 189, "Feature/toto"),
        )
    }

    @Test
    fun `branch mode builds the head ref`() {
        assertEquals(
            "Feature/toto",
            PullRequestEventListener.prBuildRefFor(config(PrBuildRef.BRANCH), 189, "Feature/toto"),
        )
    }

    @Test
    fun `branch mode falls back to the pull ref without a head ref`() {
        listOf(null, "", "   ").forEach { headRef ->
            assertEquals(
                "pull/189",
                PullRequestEventListener.prBuildRefFor(config(PrBuildRef.BRANCH), 189, headRef),
                "headRef=$headRef",
            )
        }
    }
}
