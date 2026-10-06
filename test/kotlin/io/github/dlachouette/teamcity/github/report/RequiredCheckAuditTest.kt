package io.github.dlachouette.teamcity.github.report

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// A required name that nothing posts blocks every pull request for ever.
class RequiredCheckAuditTest {

    @Test
    fun `a required name nothing posts is missing, with the branches requiring it`() {
        val r = RequiredCheckAudit.compare(
            mapOf(
                "main" to setOf("Analysis / PR Ready", "Lint"),
                "Release/2026-06" to setOf("Analysis / PR Ready"),
            ),
            produced = setOf("PR Ready", "Lint"),
        )
        assertEquals(mapOf("Analysis / PR Ready" to listOf("Release/2026-06", "main")), r.missing)
        assertEquals(listOf("PR Ready"), r.unrequired)
    }

    @Test
    fun `everything required is posted`() {
        val r = RequiredCheckAudit.compare(mapOf("main" to setOf("PR Ready")), setOf("PR Ready", "TeamCity / Nightly"))
        assertTrue(r.missing.isEmpty())
        assertEquals(listOf("TeamCity / Nightly"), r.unrequired)
    }

    @Test
    fun `names compare exactly, as GitHub requires them`() {
        val r = RequiredCheckAudit.compare(mapOf("main" to setOf("pr ready")), setOf("PR Ready"))
        assertEquals(setOf("pr ready"), r.missing.keys)
    }
}
