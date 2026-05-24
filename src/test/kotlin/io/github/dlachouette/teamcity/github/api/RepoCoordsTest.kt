package io.github.dlachouette.teamcity.github.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RepoCoordsTest {

    @Test
    fun `parse valid slug`() {
        val coords = RepoCoords.parse("Silmaen/Owl")
        assertEquals("Silmaen", coords.owner)
        assertEquals("Owl", coords.name)
        assertEquals("Silmaen/Owl", coords.slug)
    }

    @Test
    fun `parse rejects slug without slash`() {
        assertThrows(IllegalArgumentException::class.java) {
            RepoCoords.parse("nomslash")
        }
    }

    @Test
    fun `parse rejects empty owner`() {
        assertThrows(IllegalArgumentException::class.java) {
            RepoCoords.parse("/Owl")
        }
    }

    @Test
    fun `parse rejects empty name`() {
        assertThrows(IllegalArgumentException::class.java) {
            RepoCoords.parse("Silmaen/")
        }
    }

    @Test
    fun `parse keeps extra segments as part of the name`() {
        val coords = RepoCoords.parse("owner/sub/path")
        assertEquals("owner", coords.owner)
        assertEquals("sub/path", coords.name)
    }
}
