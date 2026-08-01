package io.github.dlachouette.teamcity.github.api

import io.github.dlachouette.teamcity.github.testsupport.LoggerBootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GitHubClientFilesAndAppTest {

    init { LoggerBootstrap.install() }

    @Test
    fun `parsePrFileNames extracts filenames`() {
        val json = """[{"filename":"src/a.kt"},{"filename":"docs/b.md"},{"sha":"x"}]"""
        assertEquals(listOf("src/a.kt", "docs/b.md"), GitHubClient.parsePrFileNames(json))
    }

    @Test
    fun `parsePrFileNames tolerates non-array and garbage`() {
        assertTrue(GitHubClient.parsePrFileNames("{}").isEmpty())
        assertTrue(GitHubClient.parsePrFileNames("not json").isEmpty())
    }

    @Test
    fun `parseManifestConversion extracts id, slug, pem and webhook secret`() {
        val json = """{"id":42,"slug":"my-app","pem":"-----BEGIN-----\nk\n-----END-----","webhook_secret":"s3cr3t","html_url":"https://github.com/apps/my-app"}"""
        val c = GitHubClient.parseManifestConversion(json)
        assertEquals("42", c?.appId)
        assertEquals("my-app", c?.slug)
        assertEquals("s3cr3t", c?.webhookSecret)
        assertTrue(c?.pem?.contains("BEGIN") == true)
    }

    @Test
    fun `parseManifestConversion returns null without a pem`() {
        assertEquals(null, GitHubClient.parseManifestConversion("""{"id":42,"slug":"x"}"""))
    }

    @Test
    fun `parseApp extracts slug, permissions and events`() {
        val json = """{"slug":"my-app","permissions":{"checks":"write","metadata":"read"},"events":["pull_request","check_run"]}"""
        val app = GitHubClient.parseApp(json)
        assertEquals("my-app", app?.slug)
        assertEquals("write", app?.permissions?.get("checks"))
        assertTrue(app?.events?.contains("check_run") == true)
    }

    @Test
    fun `parseCreatedToken leaves expiry null when expires_at is absent`() {
        val created = GitHubClient.parseCreatedToken("""{"token":"ghs_x"}""")
        assertEquals("ghs_x", created?.token)
        assertEquals(null, created?.expiresAt)
    }

    // One compare call answers three things, and the file count is the one to be
    // careful with: GitHub caps the array at 300 and does not say when it
    // truncated, so a count at the cap is "unknown" rather than 300.
    @Test
    fun `parseCompare reads the merge base and the counts`() {
        val json = """
            {"merge_base_commit":{"sha":"diverged"},"total_commits":4,
             "files":[{"filename":"a.cpp"},{"filename":"b.cpp"}]}
        """.trimIndent()
        val c = GitHubClient.parseCompare(json)!!
        assertEquals("diverged", c.mergeBaseSha)
        assertEquals(2, c.changedFiles)
        assertEquals(4, c.commits)
    }

    // The names are worth keeping even when the count has to be given up: a
    // truncated list is still a list of real files, and the page says "the first
    // of them" rather than pretending it is all of them.
    @Test
    fun `parseCompare keeps the file names it was given`() {
        val json = """
            {"merge_base_commit":{"sha":"d"},"total_commits":1,
             "files":[{"filename":"src/a.cpp"},{"filename":"docs/b.md"}]}
        """.trimIndent()
        val c = GitHubClient.parseCompare(json)!!
        assertEquals(listOf("src/a.cpp", "docs/b.md"), c.files)
        assertEquals(false, c.filesTruncated)
    }

    @Test
    fun `parseCompare treats a capped file array as unknown`() {
        val files = (1..GitHubClient.COMPARE_FILES_CAP).joinToString(",") { """{"filename":"f$it"}""" }
        val json = """{"merge_base_commit":{"sha":"d"},"total_commits":9,"files":[$files]}"""
        val c = GitHubClient.parseCompare(json)!!
        assertEquals(0, c.changedFiles, "a truncated array must not be reported as a count")
        assertEquals(9, c.commits)
        assertEquals(GitHubClient.COMPARE_FILES_CAP, c.files.size, "the names it did send are still real")
        assertEquals(true, c.filesTruncated)
    }

    @Test
    fun `parseCompare without a merge base is not a compare answer`() {
        assertNull(GitHubClient.parseCompare("""{"total_commits":1}"""))
        assertNull(GitHubClient.parseCompare("not json"))
    }
}
