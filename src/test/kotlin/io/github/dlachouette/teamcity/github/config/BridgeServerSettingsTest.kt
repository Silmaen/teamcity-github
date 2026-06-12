package io.github.dlachouette.teamcity.github.config

import io.github.dlachouette.teamcity.github.api.GitHubClient
import io.github.dlachouette.teamcity.github.cache.PrInfoCache
import io.github.dlachouette.teamcity.github.testsupport.LoggerBootstrap
import jetbrains.buildServer.serverSide.ServerPaths
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

// Exercises only storage-backed paths (every read either has its key set
// or uses a setting with no legacy fallback), so the tests never touch
// TeamCityProperties, which is not initialised in a plain unit-test JVM.
class BridgeServerSettingsTest {

    init { LoggerBootstrap.install() }

    private fun settings(tmp: Path, vararg pairs: Pair<String, String>): BridgeServerSettings {
        val storage = PluginSettingsStorage(stubServerPaths(tmp))
        pairs.forEach { (k, v) -> storage.set(k, v) }
        return BridgeServerSettings(storage)
    }

    @Test
    fun `boolean flags default sensibly`(@TempDir tmp: Path) {
        val s = settings(tmp)
        assertTrue(s.replayProtectionEnabled())
        assertTrue(s.metricsEnabled())
        assertFalse(s.dryRun())
        assertFalse(s.legacyAliasesEnabled())
        assertFalse(s.prCommentEnabled())
    }

    @Test
    fun `boolean flags honour stored values`(@TempDir tmp: Path) {
        val s = settings(tmp, BridgeServerSettings.KEY_DRY_RUN to "true", BridgeServerSettings.KEY_REPLAY_ENABLED to "false")
        assertTrue(s.dryRun())
        assertFalse(s.replayProtectionEnabled())
    }

    @Test
    fun `http attempts are clamped to 1-10`(@TempDir tmp: Path) {
        assertEquals(10, settings(tmp, BridgeServerSettings.KEY_HTTP_MAX_ATTEMPTS to "99").httpMaxAttempts())
        assertEquals(1, settings(tmp, BridgeServerSettings.KEY_HTTP_MAX_ATTEMPTS to "0").httpMaxAttempts())
    }

    @Test
    fun `repo allowlist matches case-insensitively and is empty-open`(@TempDir tmp: Path) {
        assertTrue(settings(tmp).isRepoAllowed("any/repo")) // empty allowlist = all allowed
        val s = settings(tmp, BridgeServerSettings.KEY_REPO_ALLOWLIST to "acme/Widget\nfoo/bar")
        assertTrue(s.isRepoAllowed("acme/widget"))
        assertTrue(s.isRepoAllowed("FOO/BAR"))
        assertFalse(s.isRepoAllowed("other/repo"))
    }

    @Test
    fun `comment author allowlist defaults to collaborators and is configurable`(@TempDir tmp: Path) {
        val def = settings(tmp)
        assertTrue(def.isCommentAuthorAllowed("MEMBER"))
        assertTrue(def.isCommentAuthorAllowed("owner")) // case-insensitive
        assertFalse(def.isCommentAuthorAllowed("NONE"))

        val custom = settings(tmp, BridgeServerSettings.KEY_COMMENT_ASSOCIATIONS to "OWNER")
        assertTrue(custom.isCommentAuthorAllowed("OWNER"))
        assertFalse(custom.isCommentAuthorAllowed("MEMBER"))
    }

    @Test
    fun `api is disabled until a token is set`(@TempDir tmp: Path) {
        assertFalse(settings(tmp).isApiEnabled())
        val s = settings(tmp, BridgeServerSettings.KEY_API_TOKEN to "secret-token")
        assertTrue(s.isApiEnabled())
        assertEquals("secret-token", s.apiToken())
    }

    @Test
    fun `applyTo pushes tuning into the live beans`(@TempDir tmp: Path) {
        val s = settings(
            tmp,
            BridgeServerSettings.KEY_API_VERSION to "2099-12-31",
            BridgeServerSettings.KEY_TTL_SECONDS to "120",
            BridgeServerSettings.KEY_STALE_GRACE_SECONDS to "30",
            BridgeServerSettings.KEY_HTTP_MAX_ATTEMPTS to "4",
            BridgeServerSettings.KEY_HTTP_BASE_DELAY_MS to "250",
        )
        val client = GitHubClient()
        val cache = PrInfoCache(client)
        s.applyTo(client, cache)
        assertEquals("2099-12-31", client.apiVersion)
        assertEquals(4, client.maxAttempts)
        assertEquals(250L, client.baseDelayMs)
        assertEquals(120_000L, cache.ttlMs)
        assertEquals(30_000L, cache.staleGraceMs)
    }

    private fun stubServerPaths(tmp: Path): ServerPaths {
        val data = tmp.toFile()
        File(data, "config").mkdirs()
        File(data, "logs").mkdirs()
        return ServerPaths(data.absolutePath)
    }
}
