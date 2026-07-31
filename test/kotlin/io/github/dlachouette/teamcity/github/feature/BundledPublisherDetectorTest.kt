package io.github.dlachouette.teamcity.github.feature

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// G15: two status producers on one build configuration means two competing
// rows per build on GitHub. The plugin warns; it never disables anything.
class BundledPublisherDetectorTest {

    @Test
    fun `recognises the bundled publisher by the shape of its type id`() {
        // The id TeamCity actually uses today...
        assertTrue(BundledPublisherDetector.isBundledPublisher("commit-status-publisher"))
        // ...and variants, so a rename does not silently disable the warning.
        listOf("commitStatusPublisher", "COMMIT-STATUS-PUBLISHER", "vcs.commit.status.publisher")
            .forEach { assertTrue(BundledPublisherDetector.isBundledPublisher(it), "type=$it") }
    }

    @Test
    fun `does not mistake other features for a status publisher`() {
        listOf(
            GitHubBridgeBuildFeature.FEATURE_TYPE,
            "perfmon",
            "swabra",
            "pullRequests",
            "commit-hook",          // "commit" alone is not enough
            "status-widget",        // "status" alone is not enough
        ).forEach { assertFalse(BundledPublisherDetector.isBundledPublisher(it), "type=$it") }
    }

    @Test
    fun `a conflict needs both producers`() {
        assertTrue(
            BundledPublisherDetector.conflicts(
                listOf(GitHubBridgeBuildFeature.FEATURE_TYPE, "commit-status-publisher"),
            )
        )
        assertFalse(BundledPublisherDetector.conflicts(listOf(GitHubBridgeBuildFeature.FEATURE_TYPE)))
        assertFalse(BundledPublisherDetector.conflicts(listOf("commit-status-publisher")))
        assertFalse(BundledPublisherDetector.conflicts(emptyList()))
    }
}
