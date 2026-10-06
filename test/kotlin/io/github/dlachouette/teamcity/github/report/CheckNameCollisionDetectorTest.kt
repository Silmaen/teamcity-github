package io.github.dlachouette.teamcity.github.report

import io.github.dlachouette.teamcity.github.report.CheckNameCollisionDetector.Collision
import io.github.dlachouette.teamcity.github.report.CheckNameCollisionDetector.Publisher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Two configurations posting one name to one repository overwrite each other's
// row on every shared commit. The self-test warns.
class CheckNameCollisionDetectorTest {

    @Test
    fun `a name shared within a repository is reported`() {
        assertEquals(
            listOf(Collision("acme/widget", "PR Ready", listOf("A_Gate", "B_Gate"))),
            CheckNameCollisionDetector.find(
                listOf(
                    Publisher("acme/widget", "PR Ready", "B_Gate"),
                    Publisher("acme/widget", "PR Ready", "A_Gate"),
                    Publisher("acme/widget", "TeamCity / Build", "Build"),
                ),
            ),
        )
    }

    @Test
    fun `the same name in two repositories is no collision`() {
        assertTrue(
            CheckNameCollisionDetector.find(
                listOf(Publisher("acme/widget", "PR Ready", "A"), Publisher("acme/gadget", "PR Ready", "B")),
            ).isEmpty(),
        )
    }

    @Test
    fun `repository slugs compare without case, as GitHub does`() {
        assertEquals(
            1,
            CheckNameCollisionDetector.find(
                listOf(Publisher("Acme/Widget", "PR Ready", "A"), Publisher("acme/widget", "PR Ready", "B")),
            ).size,
        )
    }
}
