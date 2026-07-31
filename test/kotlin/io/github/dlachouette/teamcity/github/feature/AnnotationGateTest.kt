package io.github.dlachouette.teamcity.github.feature

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Writing on somebody's diff is the one thing three levels can each veto.
class AnnotationGateTest {

    @Test
    fun `nothing said anywhere means enabled`() {
        assertTrue(AnnotationGate.enabled(serverEnabled = true, projectChain = listOf(null, null), feature = null))
    }

    @Test
    fun `the server can turn it off for everyone`() {
        assertFalse(AnnotationGate.enabled(serverEnabled = false, projectChain = listOf("true"), feature = "true"))
    }

    @Test
    fun `any project in the chain can turn it off`() {
        // Root says no.
        assertFalse(AnnotationGate.enabled(true, listOf("false", null, null), null))
        // Somewhere in the middle.
        assertFalse(AnnotationGate.enabled(true, listOf(null, "false", null), null))
        // The build configuration's own project.
        assertFalse(AnnotationGate.enabled(true, listOf(null, null, "false"), null))
    }

    @Test
    fun `the build configuration can turn it off for itself`() {
        assertFalse(AnnotationGate.enabled(true, listOf(null, null), "false"))
    }

    // The asymmetry that makes "off" enforceable in one place: a level saying
    // yes never overrules a level saying no.
    @Test
    fun `a yes lower down does not overrule a no higher up`() {
        assertFalse(AnnotationGate.enabled(true, listOf("false", "true", "true"), "true"))
        assertFalse(AnnotationGate.enabled(false, listOf("true"), "true"))
    }

    // Only the literal decides: a typo must not silently turn reporting off.
    @Test
    fun `only the literal false vetoes`() {
        assertTrue(AnnotationGate.enabled(true, listOf("", "  ", "yes", "0", "no", "FALSE_"), "nope"))
        // …case and surrounding space aside.
        assertFalse(AnnotationGate.enabled(true, listOf(" FALSE "), null))
        assertFalse(AnnotationGate.enabled(true, listOf(null), "False"))
    }

    @Test
    fun `an empty chain is not a veto`() {
        assertTrue(AnnotationGate.enabled(true, emptyList(), null))
    }

    // For the settings form: name the outermost project that says no, so a
    // ticked checkbox under a vetoing parent can explain itself.
    @Test
    fun `the vetoing project is the outermost one`() {
        assertEquals(
            "Root",
            AnnotationGate.vetoingProject(listOf("Root" to "false", "Middle" to "false", "Leaf" to null)),
        )
        assertEquals(
            "Middle",
            AnnotationGate.vetoingProject(listOf("Root" to "true", "Middle" to "false", "Leaf" to null)),
        )
        assertNull(AnnotationGate.vetoingProject(listOf("Root" to "true", "Leaf" to null)))
        assertNull(AnnotationGate.vetoingProject(emptyList()))
    }
}
