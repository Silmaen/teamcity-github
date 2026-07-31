package io.github.dlachouette.teamcity.github.api

import io.github.dlachouette.teamcity.github.testsupport.LoggerBootstrap
import org.junit.jupiter.api.Assertions.assertEquals
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
}
