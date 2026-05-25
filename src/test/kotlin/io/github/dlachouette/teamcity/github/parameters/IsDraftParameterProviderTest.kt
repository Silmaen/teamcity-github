package io.github.dlachouette.teamcity.github.parameters

import io.github.dlachouette.teamcity.github.api.PrInfo
import io.github.dlachouette.teamcity.github.api.RepoCoords
import io.github.dlachouette.teamcity.github.testsupport.LoggerBootstrap
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IsDraftParameterProviderTest {

    init { LoggerBootstrap.install() }

    private val repo = RepoCoords("acme", "widget")
    private val draftPr = pr(draft = true)
    private val readyPr = pr(draft = false)

    @Test
    fun `non-PR branches default to false`() {
        assertFalse(call("main") { _, _ -> draftPr })
        assertFalse(call("refs/heads/release/1.0") { _, _ -> draftPr })
        assertFalse(call(null) { _, _ -> draftPr })
    }

    @Test
    fun `PR branch with draft PR returns true`() {
        assertTrue(call("pull/42") { _, _ -> draftPr })
    }

    @Test
    fun `PR branch with ready PR returns false`() {
        assertFalse(call("pull/42") { _, _ -> readyPr })
    }

    @Test
    fun `PR branch with unresolved PR returns false (fail-safe)`() {
        assertFalse(call("pull/42") { _, _ -> null })
    }

    @Test
    fun `PR branch with malformed number returns false`() {
        assertFalse(call("pull/abc") { _, _ -> draftPr })
        assertFalse(call("pull/") { _, _ -> draftPr })
    }

    @Test
    fun `resolver throwing returns false instead of propagating`() {
        assertFalse(call("pull/42") { _, _ -> throw RuntimeException("simulated outage") })
    }

    @Test
    fun `repo parser throwing returns false`() {
        val result = IsDraftParameterProvider.computeIsDraft(
            branchName = "pull/42",
            resolver = { _, _ -> draftPr },
            repoCoordsParse = { throw IllegalArgumentException("bad slug") },
        )
        assertFalse(result)
    }

    private fun call(branchName: String?, resolver: (RepoCoords, Int) -> PrInfo?): Boolean =
        IsDraftParameterProvider.computeIsDraft(branchName, resolver) { repo }

    private fun pr(draft: Boolean) = PrInfo(
        number = 42, title = "x", author = "alice",
        headRef = "feature/x", baseRef = "main", headSha = "deadbeef",
        draft = draft, state = "open",
    )
}
