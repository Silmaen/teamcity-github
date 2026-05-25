package io.github.dlachouette.teamcity.github.queue

import io.github.dlachouette.teamcity.github.api.PrInfo
import io.github.dlachouette.teamcity.github.testsupport.LoggerBootstrap
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DraftBuildQueueCleanerTest {

    init { LoggerBootstrap.install() }

    @Test
    fun `shouldRemove returns true for draft PR`() {
        assertTrue(DraftBuildQueueCleaner.shouldRemove(pr(draft = true)))
    }

    @Test
    fun `shouldRemove returns false for ready PR`() {
        assertFalse(DraftBuildQueueCleaner.shouldRemove(pr(draft = false)))
    }

    private fun pr(draft: Boolean) = PrInfo(
        number = 1, title = "x", author = "alice",
        headRef = "feature/x", baseRef = "main", headSha = "abc",
        draft = draft, state = "open",
    )
}
