package io.github.dlachouette.teamcity.github.enrich

import io.github.dlachouette.teamcity.github.api.PrInfo
import io.github.dlachouette.teamcity.github.testsupport.LoggerBootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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

        // The bundled feature spells the two branch names with dots, and that
        // spelling is the one existing DSL reads — emitting only the camelCase
        // pair would make the flag miss its own purpose.
        assertEquals("feature/raycast", with[PrParameterProvider.ALIAS_PR_SOURCE_BRANCH_DOTTED])
        assertEquals("main", with[PrParameterProvider.ALIAS_PR_TARGET_BRANCH_DOTTED])
        assertEquals(
            PrParameterProvider.LEGACY_ALIAS_KEYS.toSet(),
            PrParameterProvider.LEGACY_ALIAS_KEYS.filter { with.containsKey(it) }.toSet(),
        )

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

    // The invariant that matters is not the count but the pairing: every key the
    // provider advertises must have a non-PR default, or a DSL condition on a
    // non-PR branch hits an unresolved parameter. 16 as of 1.10.0.
    @Test
    fun `every advertised key has a non-PR default`() {
        assertEquals(16, PrParameterProvider.ALL_KEYS.size)
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

    // --- what the pull request is and what it changes (1.10.0) ---

    // The values that make a build able to point back at what it is judging,
    // and a step able to diff the right range.
    @Test
    fun `publishes the PR url, the base sha, the merge base and the counts`() {
        val pr = PrInfo(
            number = 42, title = "t", author = "a", headRef = "Feature/x", baseRef = "main",
            headSha = "head", draft = false, state = "open",
            htmlUrl = "https://github.com/acme/widgets/pull/42",
            baseSha = "basehead", changedFiles = 7, additions = 120, deletions = 3, commits = 2,
            mergeBaseSha = "diverged", labels = listOf("ci", "area:api"),
        )
        val p = PrParameterProvider.computeParams("pull/42") { pr }
        assertEquals("https://github.com/acme/widgets/pull/42", p[PrParameterProvider.PARAM_PR_URL])
        assertEquals("basehead", p[PrParameterProvider.PARAM_PR_BASE_SHA])
        assertEquals("diverged", p[PrParameterProvider.PARAM_PR_MERGE_BASE])
        assertEquals("7", p[PrParameterProvider.PARAM_PR_CHANGED_FILES])
        assertEquals("120", p[PrParameterProvider.PARAM_PR_ADDITIONS])
        assertEquals("3", p[PrParameterProvider.PARAM_PR_DELETIONS])
        assertEquals("2", p[PrParameterProvider.PARAM_PR_COMMITS])
        assertEquals("ci,area:api", p[PrParameterProvider.PARAM_PR_LABELS])
    }

    // The base branch's head is NOT the merge base, and the two must never be
    // conflated: a diff against the head also contains what landed on the base
    // since the branch started.
    @Test
    fun `the merge base is empty rather than wrong when it was not resolved`() {
        val pr = PrInfo(
            number = 42, title = "t", author = "a", headRef = "Feature/x", baseRef = "main",
            headSha = "head", draft = false, state = "open", baseSha = "basehead",
        )
        val p = PrParameterProvider.computeParams("pull/42") { pr }
        assertEquals("basehead", p[PrParameterProvider.PARAM_PR_BASE_SHA])
        assertEquals("", p[PrParameterProvider.PARAM_PR_MERGE_BASE])
    }

    // `GET /commits/{sha}/pulls` returns PR objects without the counts. Zero
    // would read as "this pull request changes nothing".
    @Test
    fun `a count GitHub did not send is empty, not zero`() {
        val pr = PrInfo(
            number = 42, title = "t", author = "a", headRef = "Feature/x", baseRef = "main",
            headSha = "head", draft = false, state = "open",
        )
        val p = PrParameterProvider.computeParams("pull/42") { pr }
        assertEquals("", p[PrParameterProvider.PARAM_PR_CHANGED_FILES])
        assertEquals("", p[PrParameterProvider.PARAM_PR_COMMITS])
        assertEquals("", p[PrParameterProvider.PARAM_PR_LABELS])
    }

    // Every key is always emitted, so DSL conditions never hit an unresolved
    // parameter — the new ones included.
    @Test
    fun `the new keys are part of the always-emitted set`() {
        listOf(
            PrParameterProvider.PARAM_PR_URL,
            PrParameterProvider.PARAM_PR_BASE_SHA,
            PrParameterProvider.PARAM_PR_MERGE_BASE,
            PrParameterProvider.PARAM_PR_CHANGED_FILES,
            PrParameterProvider.PARAM_PR_LABELS,
        ).forEach { key ->
            assertTrue(PrParameterProvider.ALL_KEYS.contains(key), key)
            assertTrue(PrParameterProvider.DEFAULT_NON_PR_PARAMS.containsKey(key), key)
        }
    }
}
