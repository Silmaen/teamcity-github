package io.github.dlachouette.teamcity.github.parameters

import io.github.dlachouette.teamcity.github.api.PrInfo
import io.github.dlachouette.teamcity.github.testsupport.LoggerBootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PrParameterProviderTest {

    init { LoggerBootstrap.install() }

    private val draftPr = pr(
        number = 189,
        title = "Add raycast shadows",
        author = "alice",
        headRef = "feature/raycast",
        baseRef = "main",
        headSha = "deadbeef1234",
        draft = true,
    )
    private val readyPr = draftPr.copy(draft = false)

    @Test
    fun `non-PR branches default to isPullRequest=false and empty PR vars`() {
        val params = PrParameterProvider.computeParams("main") { _ -> draftPr }
        assertEquals("false", params[PrParameterProvider.PARAM_IS_PULL_REQUEST])
        assertEquals("false", params[PrParameterProvider.PARAM_IS_DRAFT])
        assertEquals("", params[PrParameterProvider.PARAM_PR_NUMBER])
        assertEquals("", params[PrParameterProvider.PARAM_PR_TITLE])
        assertEquals("", params[PrParameterProvider.PARAM_PR_AUTHOR])
        assertEquals("", params[PrParameterProvider.PARAM_PR_SOURCE_BRANCH])
        assertEquals("", params[PrParameterProvider.PARAM_PR_TARGET_BRANCH])
        assertEquals("", params[PrParameterProvider.PARAM_PR_HEAD_SHA])
    }

    @Test
    fun `null branch defaults to non-PR`() {
        val params = PrParameterProvider.computeParams(null) { _ -> draftPr }
        assertEquals("false", params[PrParameterProvider.PARAM_IS_PULL_REQUEST])
    }

    @Test
    fun `PR branch with draft PR populates all keys and isDraft=true`() {
        val params = PrParameterProvider.computeParams("pull/189") { _ -> draftPr }
        assertEquals("true", params[PrParameterProvider.PARAM_IS_PULL_REQUEST])
        assertEquals("true", params[PrParameterProvider.PARAM_IS_DRAFT])
        assertEquals("189", params[PrParameterProvider.PARAM_PR_NUMBER])
        assertEquals("Add raycast shadows", params[PrParameterProvider.PARAM_PR_TITLE])
        assertEquals("alice", params[PrParameterProvider.PARAM_PR_AUTHOR])
        assertEquals("feature/raycast", params[PrParameterProvider.PARAM_PR_SOURCE_BRANCH])
        assertEquals("main", params[PrParameterProvider.PARAM_PR_TARGET_BRANCH])
        assertEquals("deadbeef1234", params[PrParameterProvider.PARAM_PR_HEAD_SHA])
    }

    @Test
    fun `PR branch with ready PR sets isDraft=false`() {
        val params = PrParameterProvider.computeParams("pull/189") { _ -> readyPr }
        assertEquals("true", params[PrParameterProvider.PARAM_IS_PULL_REQUEST])
        assertEquals("false", params[PrParameterProvider.PARAM_IS_DRAFT])
        assertEquals("Add raycast shadows", params[PrParameterProvider.PARAM_PR_TITLE])
    }

    @Test
    fun `PR branch with unresolvable PR keeps number from branch name and empties the rest`() {
        val params = PrParameterProvider.computeParams("pull/42") { _ -> null }
        assertEquals("true", params[PrParameterProvider.PARAM_IS_PULL_REQUEST])
        assertEquals("false", params[PrParameterProvider.PARAM_IS_DRAFT])
        assertEquals("42", params[PrParameterProvider.PARAM_PR_NUMBER])
        assertEquals("", params[PrParameterProvider.PARAM_PR_TITLE])
        assertEquals("", params[PrParameterProvider.PARAM_PR_AUTHOR])
        assertEquals("", params[PrParameterProvider.PARAM_PR_SOURCE_BRANCH])
        assertEquals("", params[PrParameterProvider.PARAM_PR_TARGET_BRANCH])
        assertEquals("", params[PrParameterProvider.PARAM_PR_HEAD_SHA])
    }

    @Test
    fun `malformed PR number falls back to non-PR defaults`() {
        val params = PrParameterProvider.computeParams("pull/abc") { _ -> draftPr }
        assertEquals("false", params[PrParameterProvider.PARAM_IS_PULL_REQUEST])
        assertEquals("", params[PrParameterProvider.PARAM_PR_NUMBER])
    }

    @Test
    fun `resolver throwing returns isPullRequest=true with empty details`() {
        val params = PrParameterProvider.computeParams("pull/42") { _ ->
            throw RuntimeException("simulated GitHub outage")
        }
        assertEquals("true", params[PrParameterProvider.PARAM_IS_PULL_REQUEST])
        assertEquals("42", params[PrParameterProvider.PARAM_PR_NUMBER])
        assertEquals("", params[PrParameterProvider.PARAM_PR_TITLE])
    }

    @Test
    fun `legacy aliases are emitted only when enabled and only for PR builds`() {
        val without = PrParameterProvider.computeParams("pull/189", legacyAliases = false) { _ -> readyPr }
        assertEquals(null, without[PrParameterProvider.ALIAS_PR_NUMBER])

        val with = PrParameterProvider.computeParams("pull/189", legacyAliases = true) { _ -> readyPr }
        assertEquals("189", with[PrParameterProvider.ALIAS_PR_NUMBER])
        assertEquals("Add raycast shadows", with[PrParameterProvider.ALIAS_PR_TITLE])
        assertEquals("feature/raycast", with[PrParameterProvider.ALIAS_PR_SOURCE_BRANCH])
        assertEquals("main", with[PrParameterProvider.ALIAS_PR_TARGET_BRANCH])

        // Non-PR builds never get aliases even when enabled.
        val nonPr = PrParameterProvider.computeParams("main", legacyAliases = true) { _ -> readyPr }
        assertEquals(null, nonPr[PrParameterProvider.ALIAS_PR_NUMBER])
    }

    // ----- branch builds attached to their PR via the head commit -----

    @Test
    fun `branch build whose commit heads a PR gets the full PR params`() {
        val params = PrParameterProvider.computeParams(
            branchName = "Feature/raycast",
            headSha = "deadbeef1234",
            prByCommitResolver = { readyPr },
        ) { _ -> error("the number-based resolver must not be used on a branch build") }

        assertEquals("true", params[PrParameterProvider.PARAM_IS_PULL_REQUEST])
        assertEquals("false", params[PrParameterProvider.PARAM_IS_DRAFT])
        assertEquals("189", params[PrParameterProvider.PARAM_PR_NUMBER])
        assertEquals("Add raycast shadows", params[PrParameterProvider.PARAM_PR_TITLE])
        assertEquals("feature/raycast", params[PrParameterProvider.PARAM_PR_SOURCE_BRANCH])
        assertEquals("main", params[PrParameterProvider.PARAM_PR_TARGET_BRANCH])
        assertEquals("deadbeef1234", params[PrParameterProvider.PARAM_PR_HEAD_SHA])
    }

    @Test
    fun `branch build carries the PR draft state`() {
        val params = PrParameterProvider.computeParams(
            branchName = "Feature/raycast",
            headSha = "deadbeef1234",
            prByCommitResolver = { draftPr },
        ) { _ -> null }
        assertEquals("true", params[PrParameterProvider.PARAM_IS_DRAFT])
    }

    @Test
    fun `branch build with no PR for the commit stays non-PR`() {
        val params = PrParameterProvider.computeParams(
            branchName = "Feature/raycast",
            headSha = "deadbeef1234",
            prByCommitResolver = { null },
        ) { _ -> readyPr }
        assertEquals(PrParameterProvider.DEFAULT_NON_PR_PARAMS, params)
    }

    @Test
    fun `branch build without a resolved revision never looks a PR up`() {
        val params = PrParameterProvider.computeParams(
            branchName = "Feature/raycast",
            headSha = null,
            prByCommitResolver = { error("must not be called without a head SHA") },
        ) { _ -> readyPr }
        assertEquals(PrParameterProvider.DEFAULT_NON_PR_PARAMS, params)
    }

    @Test
    fun `commit resolver throwing degrades to non-PR defaults`() {
        val params = PrParameterProvider.computeParams(
            branchName = "Feature/raycast",
            headSha = "deadbeef1234",
            prByCommitResolver = { throw RuntimeException("simulated GitHub outage") },
        ) { _ -> null }
        assertEquals(PrParameterProvider.DEFAULT_NON_PR_PARAMS, params)
    }

    @Test
    fun `legacy aliases are emitted for a branch build attached to a PR`() {
        val params = PrParameterProvider.computeParams(
            branchName = "Feature/raycast",
            legacyAliases = true,
            headSha = "deadbeef1234",
            prByCommitResolver = { readyPr },
        ) { _ -> null }
        assertEquals("189", params[PrParameterProvider.ALIAS_PR_NUMBER])
        assertEquals("feature/raycast", params[PrParameterProvider.ALIAS_PR_SOURCE_BRANCH])
    }

    @Test
    fun `a pull ref never uses the commit resolver`() {
        val params = PrParameterProvider.computeParams(
            branchName = "pull/189",
            headSha = "deadbeef1234",
            prByCommitResolver = { error("pull/N refs resolve by number") },
        ) { _ -> readyPr }
        assertEquals("189", params[PrParameterProvider.PARAM_PR_NUMBER])
    }

    @Test
    fun `ALL_KEYS lists exactly 8 keys and matches DEFAULT_NON_PR_PARAMS`() {
        assertEquals(8, PrParameterProvider.ALL_KEYS.size)
        assertEquals(PrParameterProvider.ALL_KEYS.toSet(), PrParameterProvider.DEFAULT_NON_PR_PARAMS.keys)
    }

    private fun pr(
        number: Int,
        title: String,
        author: String,
        headRef: String,
        baseRef: String,
        headSha: String,
        draft: Boolean,
    ) = PrInfo(
        number = number, title = title, author = author,
        headRef = headRef, baseRef = baseRef, headSha = headSha,
        draft = draft, state = "open",
    )
}
