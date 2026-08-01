package io.github.dlachouette.teamcity.github.web

import io.github.dlachouette.teamcity.github.enrich.PrParameterProvider as P
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// The build page's "Pull request" tab is built from the build's own parameters,
// so its whole decision surface is a map lookup — and therefore testable without
// a server.
class PrTabModelTest {

    private fun model(vararg pairs: Pair<String, String>): PrTabModel? {
        val map = pairs.toMap()
        return PrTabModel.from { map[it] }
    }

    private val full = arrayOf(
        P.PARAM_IS_PULL_REQUEST to "true",
        P.PARAM_IS_DRAFT to "true",
        P.PARAM_PR_NUMBER to "6",
        P.PARAM_PR_TITLE to "Add raycast shadows",
        P.PARAM_PR_AUTHOR to "alice",
        P.PARAM_PR_SOURCE_BRANCH to "Feature/raycast",
        P.PARAM_PR_TARGET_BRANCH to "main",
        P.PARAM_PR_HEAD_SHA to "headsha",
        P.PARAM_PR_BASE_SHA to "basehead",
        P.PARAM_PR_MERGE_BASE to "diverged",
        P.PARAM_PR_URL to "https://github.com/acme/widgets/pull/6",
        P.PARAM_PR_CHANGED_FILES to "7",
        P.PARAM_PR_ADDITIONS to "120",
        P.PARAM_PR_DELETIONS to "3",
        P.PARAM_PR_COMMITS to "2",
        P.PARAM_PR_LABELS to "ci, area:api",
    )

    @Test
    fun `reads every field from the build's parameters`() {
        val m = model(*full)!!
        assertEquals(6, m.number)
        assertEquals("https://github.com/acme/widgets/pull/6", m.url)
        assertEquals("Add raycast shadows", m.title)
        assertEquals("alice", m.author)
        assertTrue(m.draft)
        assertEquals("Feature/raycast", m.sourceBranch)
        assertEquals("main", m.targetBranch)
        assertEquals("diverged", m.mergeBase)
        assertEquals(listOf("ci", "area:api"), m.labels)
    }

    // What makes the tab absent instead of empty on every build of `main`.
    @Test
    fun `a build that is not a pull-request build has no model`() {
        assertNull(model(P.PARAM_IS_PULL_REQUEST to "false", P.PARAM_PR_NUMBER to ""))
        assertNull(model())
    }

    // The number is the only load-bearing field: without it there is nothing to
    // show and nothing to link to.
    @Test
    fun `a missing or absurd number means no tab`() {
        assertNull(model(P.PARAM_IS_PULL_REQUEST to "true", P.PARAM_PR_NUMBER to ""))
        assertNull(model(P.PARAM_IS_PULL_REQUEST to "true", P.PARAM_PR_NUMBER to "0"))
        assertNull(model(P.PARAM_IS_PULL_REQUEST to "true", P.PARAM_PR_NUMBER to "not-a-number"))
    }

    // A build from before these parameters existed still gets a tab; it just
    // shows less. Degrading field by field beats refusing to render.
    @Test
    fun `an older build degrades to what it carries`() {
        val m = model(
            P.PARAM_IS_PULL_REQUEST to "true",
            P.PARAM_PR_NUMBER to "6",
            P.PARAM_PR_TITLE to "Older build",
        )!!
        assertEquals(6, m.number)
        assertEquals("", m.url)
        assertEquals("", m.mergeBase)
        assertEquals(emptyList<String>(), m.labels)
    }

    // The diff range is the reason the merge base is published at all.
    @Test
    fun `the diff range spans the merge base to the head`() {
        assertEquals("diverged..headsha", model(*full)!!.diffRange)
    }

    // And it is empty rather than wrong: falling back to the base branch's head
    // would widen the diff to everything that landed on the base meanwhile.
    @Test
    fun `no merge base means no diff range, not a guessed one`() {
        val m = model(
            P.PARAM_IS_PULL_REQUEST to "true",
            P.PARAM_PR_NUMBER to "6",
            P.PARAM_PR_HEAD_SHA to "headsha",
            P.PARAM_PR_BASE_SHA to "basehead",
        )!!
        assertEquals("", m.diffRange)
        assertEquals("basehead", m.baseSha)
    }

    @Test
    fun `an empty label list is empty, not a list of one blank`() {
        val m = model(
            P.PARAM_IS_PULL_REQUEST to "true",
            P.PARAM_PR_NUMBER to "6",
            P.PARAM_PR_LABELS to "",
        )!!
        assertEquals(emptyList<String>(), m.labels)
    }

    // --- derived links ---
    //
    // The tab offers a page of GitHub links without a second parameter to carry a
    // hostname, by cutting the pull request's own URL apart. So the whole set has
    // to appear together, or not at all.

    @Test
    fun `every GitHub link is derived from the PR url`() {
        val m = model(*full)!!
        assertEquals("https://github.com/acme/widgets", m.repoWebRoot)
        assertEquals("https://github.com/acme/widgets/pull/6/checks", m.checksUrl)
        assertEquals("https://github.com/acme/widgets/pull/6/files", m.filesUrl)
        assertEquals("https://github.com/acme/widgets/pull/6/commits", m.commitsUrl)
        assertEquals("https://github.com/acme/widgets/commit/headsha", m.headCommitUrl)
        assertEquals("https://github.com/acme/widgets/compare/diverged...headsha", m.compareUrl)
    }

    // GitHub Enterprise is a different host and nothing in the derivation assumes
    // github.com — which is the point of deriving instead of composing.
    @Test
    fun `derivation follows the host it was given`() {
        val m = model(
            P.PARAM_IS_PULL_REQUEST to "true",
            P.PARAM_PR_NUMBER to "6",
            P.PARAM_PR_HEAD_SHA to "headsha",
            P.PARAM_PR_MERGE_BASE to "diverged",
            P.PARAM_PR_URL to "https://github.example.com/acme/widgets/pull/6",
        )!!
        assertEquals("https://github.example.com/acme/widgets", m.repoWebRoot)
        assertEquals("https://github.example.com/acme/widgets/compare/diverged...headsha", m.compareUrl)
    }

    @Test
    fun `no url means no derived link at all`() {
        val m = model(P.PARAM_IS_PULL_REQUEST to "true", P.PARAM_PR_NUMBER to "6", P.PARAM_PR_HEAD_SHA to "h")!!
        assertEquals("", m.repoWebRoot)
        assertEquals("", m.checksUrl)
        assertEquals("", m.filesUrl)
        assertEquals("", m.headCommitUrl)
        assertEquals("", m.compareUrl)
    }

    // A URL we do not recognise is not one to build other URLs from.
    @Test
    fun `an unexpected url shape yields no repository root`() {
        val m = model(
            P.PARAM_IS_PULL_REQUEST to "true",
            P.PARAM_PR_NUMBER to "6",
            P.PARAM_PR_HEAD_SHA to "headsha",
            P.PARAM_PR_URL to "https://example.com/something/else",
        )!!
        assertEquals("", m.repoWebRoot)
        assertEquals("", m.headCommitUrl)
        // The PR tabs still work: they only append to the URL we were given.
        assertEquals("https://example.com/something/else/checks", m.checksUrl)
    }

    // Without a merge base there is no compare link, for the same reason there is
    // no diff range: the alternative range is the wrong one.
    @Test
    fun `no merge base means no compare link`() {
        val m = model(
            P.PARAM_IS_PULL_REQUEST to "true",
            P.PARAM_PR_NUMBER to "6",
            P.PARAM_PR_HEAD_SHA to "headsha",
            P.PARAM_PR_BASE_SHA to "basehead",
            P.PARAM_PR_URL to "https://github.com/acme/widgets/pull/6",
        )!!
        assertEquals("", m.compareUrl)
        assertEquals("https://github.com/acme/widgets/commit/headsha", m.headCommitUrl)
    }
}
