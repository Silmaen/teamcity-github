package io.github.dlachouette.teamcity.github.report

import jetbrains.buildServer.BuildProblemTypes
import jetbrains.buildServer.messages.ErrorData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FailureClassifierTest {

    @Test
    fun `a build with no reported problem is a code failure`() {
        val c = FailureClassifier.classify(emptyList())
        assertEquals(FailureKind.CODE, c.kind)
        assertFalse(c.infrastructural)
    }

    @Test
    fun `failing tests are the code's problem`() {
        val c = FailureClassifier.classify(listOf(BuildProblemTypes.TC_FAILED_TESTS_TYPE))
        assertEquals(FailureKind.CODE, c.kind)
    }

    @Test
    fun `a compile error and a non-zero exit code are the code's problem`() {
        listOf(
            BuildProblemTypes.TC_COMPILATION_ERROR_TYPE,
            BuildProblemTypes.TC_EXIT_CODE_TYPE,
            BuildProblemTypes.TC_ERROR_MESSAGE_TYPE,
            BuildProblemTypes.TC_USER_PROVIDED_TYPE,
        ).forEach { type ->
            assertEquals(FailureKind.CODE, FailureClassifier.classify(listOf(type)).kind, type)
        }
    }

    @Test
    fun `a lost checkout is infrastructural, and says so`() {
        val c = FailureClassifier.classify(listOf(ErrorData.UPDATE_SOURCES_TYPE))
        assertEquals(FailureKind.INFRASTRUCTURE, c.kind)
        assertTrue(c.infrastructural)
        // TeamCity's own wording, so the Check Run and the TeamCity page agree.
        assertEquals("Error while applying patch", c.cause)
    }

    @Test
    fun `every internal error type TeamCity knows is infrastructural`() {
        listOf(
            ErrorData.CHECKING_FOR_CHANGES_ERROR_TYPE,
            ErrorData.ARTIFACT_DEPENDENCY_ERROR_TYPE,
            ErrorData.PREPARATION_FAILURE_TYPE,
            ErrorData.DIRECTORY_CREATION_ERROR_TYPE,
            ErrorData.BUILD_RUNNER_ERROR_TYPE,
            ErrorData.INACCESSIBLE_EXTERNAL_DEPENDENCY_ERROR_TYPE,
        ).forEach { type ->
            val c = FailureClassifier.classify(listOf(type))
            assertEquals(FailureKind.INFRASTRUCTURE, c.kind, type)
            assertNotNull(c.cause, type)
        }
    }

    // The reason DEPENDENCY exists: TeamCity groups a failed snapshot
    // dependency with its internal errors, but that dependency may have failed
    // on this very PR's code, so it must not be excused as a CI hiccup.
    @Test
    fun `a failed snapshot dependency is named but not called infrastructure`() {
        val c = FailureClassifier.classify(listOf(ErrorData.SNAPSHOT_DEPENDENCY_ERROR_TYPE))
        assertEquals(FailureKind.DEPENDENCY, c.kind)
        assertFalse(c.infrastructural)
        assertEquals("Snapshot dependency failure", c.cause)
    }

    @Test
    fun `one code problem among infrastructural ones keeps the failure the code's`() {
        val c = FailureClassifier.classify(
            listOf(ErrorData.ARTIFACT_DEPENDENCY_ERROR_TYPE, BuildProblemTypes.TC_FAILED_TESTS_TYPE),
        )
        assertEquals(FailureKind.CODE, c.kind)
    }

    @Test
    fun `a dependency failure alongside a real infrastructure one reports the infrastructure one`() {
        val c = FailureClassifier.classify(
            listOf(ErrorData.SNAPSHOT_DEPENDENCY_ERROR_TYPE, ErrorData.CHECKING_FOR_CHANGES_ERROR_TYPE),
        )
        assertEquals(FailureKind.INFRASTRUCTURE, c.kind)
        assertEquals("Unable to collect changes", c.cause)
    }

    @Test
    fun `blank problem types are ignored`() {
        assertEquals(FailureKind.CODE, FailureClassifier.classify(listOf("", "   ")).kind)
    }

    @Test
    fun `an unknown type is classified as the code's problem`() {
        // Not in TeamCity's internal-error set: we do not get to excuse it.
        assertEquals(FailureKind.CODE, FailureClassifier.classify(listOf("SOME_FUTURE_PROBLEM")).kind)
    }

    @Test
    fun `a type with no description is humanised rather than dropped`() {
        assertEquals("Some future problem", FailureClassifier.labelOf("SOME_FUTURE_PROBLEM"))
        assertEquals("Some future problem", FailureClassifier.labelOf("SOME_FUTURE_PROBLEM_TYPE"))
    }
}
