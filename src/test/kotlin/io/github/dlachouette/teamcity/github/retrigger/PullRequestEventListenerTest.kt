package io.github.dlachouette.teamcity.github.retrigger

import io.github.dlachouette.teamcity.github.api.RepoCoords
import io.github.dlachouette.teamcity.github.testsupport.LoggerBootstrap
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PullRequestEventListenerTest {

    init { LoggerBootstrap.install() }

    @Test
    fun `shouldEnqueue accepts ready_for_review regardless of draft flag`() {
        // ready_for_review fires only on the draft->ready transition,
        // so by GitHub's contract draft is always false here. We accept
        // unconditionally — defensive against malformed payloads.
        assertTrue(PullRequestEventListener.shouldEnqueue(payload(PrAction.READY_FOR_REVIEW, draft = false)))
        assertTrue(PullRequestEventListener.shouldEnqueue(payload(PrAction.READY_FOR_REVIEW, draft = true)))
    }

    @Test
    fun `shouldEnqueue accepts opened when not draft`() {
        assertTrue(PullRequestEventListener.shouldEnqueue(payload(PrAction.OPENED, draft = false)))
    }

    @Test
    fun `shouldEnqueue rejects opened when draft`() {
        assertFalse(PullRequestEventListener.shouldEnqueue(payload(PrAction.OPENED, draft = true)))
    }

    @Test
    fun `shouldEnqueue accepts synchronize when not draft`() {
        assertTrue(PullRequestEventListener.shouldEnqueue(payload(PrAction.SYNCHRONIZE, draft = false)))
    }

    @Test
    fun `shouldEnqueue rejects synchronize when draft`() {
        assertFalse(PullRequestEventListener.shouldEnqueue(payload(PrAction.SYNCHRONIZE, draft = true)))
    }

    @Test
    fun `PrAction fromString maps the three supported actions`() {
        assertTrue(PrAction.fromString("opened") == PrAction.OPENED)
        assertTrue(PrAction.fromString("ready_for_review") == PrAction.READY_FOR_REVIEW)
        assertTrue(PrAction.fromString("synchronize") == PrAction.SYNCHRONIZE)
    }

    @Test
    fun `PrAction fromString returns null for unrelated actions`() {
        assertTrue(PrAction.fromString("closed") == null)
        assertTrue(PrAction.fromString("edited") == null)
        assertTrue(PrAction.fromString("labeled") == null)
        assertTrue(PrAction.fromString("") == null)
    }

    private fun payload(action: PrAction, draft: Boolean) = PrEventPayload(
        action = action,
        repo = RepoCoords.parse("acme/widget"),
        prNumber = 1,
        headSha = "abc123",
        baseRef = "main",
        headRef = "feature/x",
        draft = draft,
    )
}
