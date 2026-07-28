package io.github.dlachouette.teamcity.github.api

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.dlachouette.teamcity.github.config.BridgeServerSettings
import io.github.dlachouette.teamcity.github.config.PluginSettingsStorage
import io.github.dlachouette.teamcity.github.testsupport.LoggerBootstrap
import jetbrains.buildServer.serverSide.ServerPaths
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.util.Base64

class AppManagerTest {

    init { LoggerBootstrap.install() }

    companion object {
        private lateinit var pkcs8Pem: String

        @JvmStatic
        @BeforeAll
        fun bootstrap() {
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val b64 = Base64.getEncoder().encodeToString((kp.private as RSAPrivateKey).encoded)
            pkcs8Pem = buildString {
                appendLine("-----BEGIN PRIVATE KEY-----")
                b64.chunked(64).forEach { appendLine(it) }
                appendLine("-----END PRIVATE KEY-----")
            }
        }

        private val MAPPER = ObjectMapper()
    }

    private fun manager(tmp: Path, app: AppInfo?): Pair<AppManager, PluginSettingsStorage> {
        val storage = PluginSettingsStorage(stubServerPaths(tmp))
        val settings = BridgeServerSettings(storage)
        val client = object : GitHubClient() {
            override fun getApp(jwt: String, apiBase: String): AppInfo? = app
        }
        return AppManager(client, settings, storage) to storage
    }

    @Test
    fun `buildManifest carries webhook url, required permissions and events`(@TempDir tmp: Path) {
        val (mgr, _) = manager(tmp, null)
        val json = MAPPER.readTree(mgr.buildManifest("My App", "https://tc/hook", "https://tc/cb"))
        assertEquals("https://tc/hook", json.path("hook_attributes").path("url").asText())
        assertEquals("https://tc/cb", json.path("redirect_url").asText())
        assertEquals("write", json.path("default_permissions").path("checks").asText())
        assertEquals("write", json.path("default_permissions").path("pull_requests").asText())
        val events = json.path("default_events").map { it.asText() }
        assertTrue(events.containsAll(AppManager.REQUIRED_EVENTS))
    }

    @Test
    fun `permissionSatisfied respects the none read write admin ordering`() {
        assertTrue(AppManager.permissionSatisfied("write", "read"))
        assertTrue(AppManager.permissionSatisfied("admin", "write"))
        assertTrue(AppManager.permissionSatisfied("read", "read"))
        assertFalse(AppManager.permissionSatisfied("read", "write"))
        assertFalse(AppManager.permissionSatisfied(null, "read"))
    }

    @Test
    fun `verify reports ok when the App grants everything required`(@TempDir tmp: Path) {
        val app = AppInfo(
            slug = "my-app",
            permissions = mapOf("metadata" to "read", "checks" to "write", "pull_requests" to "write", "contents" to "read"),
            events = AppManager.REQUIRED_EVENTS,
        )
        val (mgr, storage) = manager(tmp, app)
        storage.set(BridgeServerSettings.KEY_APP_ID, "123")
        storage.set(BridgeServerSettings.KEY_APP_PRIVATE_KEY, pkcs8Pem)

        val v = mgr.verify()
        assertTrue(v != null && v.ok, "expected ok, got $v")
    }

    @Test
    fun `verify flags missing permissions and events`(@TempDir tmp: Path) {
        val app = AppInfo(
            slug = "my-app",
            permissions = mapOf("metadata" to "read", "checks" to "read"), // checks too weak, pull_requests missing
            events = listOf("pull_request"), // missing review/review_comment/check_run/check_suite
        )
        val (mgr, storage) = manager(tmp, app)
        storage.set(BridgeServerSettings.KEY_APP_ID, "123")
        storage.set(BridgeServerSettings.KEY_APP_PRIVATE_KEY, pkcs8Pem)

        val v = mgr.verify()!!
        assertFalse(v.ok)
        assertTrue(v.missingPermissions.contains("checks"))
        assertTrue(v.missingPermissions.contains("pull_requests"))
        assertTrue(v.missingEvents.contains("pull_request_review_comment"))
    }

    @Test
    fun `verify returns null when no managed app is configured`(@TempDir tmp: Path) {
        val (mgr, _) = manager(tmp, null)
        assertEquals(null, mgr.verify())
    }

    private fun stubServerPaths(tmp: Path): ServerPaths {
        val data = tmp.toFile()
        File(data, "config").mkdirs()
        File(data, "logs").mkdirs()
        return ServerPaths(data.absolutePath)
    }
}
