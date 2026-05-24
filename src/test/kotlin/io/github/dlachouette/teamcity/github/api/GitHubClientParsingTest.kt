package io.github.dlachouette.teamcity.github.api

import io.github.dlachouette.teamcity.github.testsupport.LoggerBootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GitHubClientParsingTest {

    init { LoggerBootstrap.install() }

    @Test
    fun `parse minimal valid PR JSON`() {
        val json = """
            {
              "number": 189,
              "title": "Add raycast shadows",
              "state": "open",
              "draft": true,
              "user": {"login": "alice"},
              "head": {"ref": "Feature/raycast-shadows", "sha": "abc1234"},
              "base": {"ref": "main"}
            }
        """.trimIndent()

        val info = GitHubClient.parsePrInfo(json)
        assertNotNull(info)
        info!!
        assertEquals(189, info.number)
        assertEquals("Add raycast shadows", info.title)
        assertEquals("alice", info.author)
        assertEquals("Feature/raycast-shadows", info.headRef)
        assertEquals("abc1234", info.headSha)
        assertEquals("main", info.baseRef)
        assertTrue(info.draft)
        assertEquals("open", info.state)
    }

    @Test
    fun `parse PR JSON missing optional fields`() {
        val json = """{"number": 1, "head": {}, "base": {}}"""

        val info = GitHubClient.parsePrInfo(json)
        assertNotNull(info)
        info!!
        assertEquals(1, info.number)
        assertEquals("", info.title)
        assertEquals("", info.author)
        assertFalse(info.draft)
        assertEquals("open", info.state)
    }

    @Test
    fun `parse rejects PR JSON without number`() {
        val json = """{"title": "ghost PR"}"""
        assertNull(GitHubClient.parsePrInfo(json))
    }

    @Test
    fun `parse returns null on invalid JSON`() {
        assertNull(GitHubClient.parsePrInfo("not json {"))
    }
}
