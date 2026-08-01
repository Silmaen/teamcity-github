package io.github.dlachouette.teamcity.github.report

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReportHelpersTest {

    // Shortening the Check Run name is a **rename**: GitHub keys a row on
    // (name, head_sha) and a branch protection rule requires the string
    // literally. So the stripping is deliberately timid.

    private val full = "TeamCity / Sandbox / test_ci / PR / Build / Linux / Build (Linux, x64, Release)"

    @Test
    fun `strips the declared prefix, with or without its trailing slash`() {
        val want = "Build / Linux / Build (Linux, x64, Release)"
        assertEquals(want, stripCheckNamePrefix(full, "TeamCity / Sandbox / test_ci / PR /"))
        assertEquals(want, stripCheckNamePrefix(full, "TeamCity / Sandbox / test_ci / PR"))
        // Typing it with stray space around is the commonest form-filling slip.
        assertEquals(want, stripCheckNamePrefix(full, "  TeamCity / Sandbox / test_ci / PR /  "))
    }

    @Test
    fun `no prefix declared changes nothing`() {
        assertEquals(full, stripCheckNamePrefix(full, null))
        assertEquals(full, stripCheckNamePrefix(full, ""))
        assertEquals(full, stripCheckNamePrefix(full, "   "))
    }

    // A stale setting — after the project was moved or renamed — must do nothing
    // rather than mangle the name into something no rule matches.
    @Test
    fun `a prefix that does not match is ignored`() {
        assertEquals(full, stripCheckNamePrefix(full, "TeamCity / Other / tree /"))
    }

    // A Check Run with no name has no identity at all.
    @Test
    fun `stripping everything is refused`() {
        assertEquals(full, stripCheckNamePrefix(full, full))
        assertEquals(full, stripCheckNamePrefix(full, "$full / "))
    }

    @Test
    fun `matching ignores case, since the project tree is what it is`() {
        assertEquals(
            "Build / Linux / Build (Linux, x64, Release)",
            stripCheckNamePrefix(full, "teamcity / sandbox / test_ci / pr /"),
        )
    }
}
