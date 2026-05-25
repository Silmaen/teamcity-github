package io.github.dlachouette.teamcity.github.config

import io.github.dlachouette.teamcity.github.testsupport.LoggerBootstrap
import jetbrains.buildServer.serverSide.ServerPaths
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class PluginSettingsStorageTest {

    init { LoggerBootstrap.install() }

    @Test
    fun `returns null when no file exists`(@TempDir tmp: Path) {
        val storage = PluginSettingsStorage(stubServerPaths(tmp))
        assertNull(storage.secret())
    }

    @Test
    fun `setSecret persists and is readable`(@TempDir tmp: Path) {
        val storage = PluginSettingsStorage(stubServerPaths(tmp))
        storage.setSecret("super-strong-random-value")
        assertEquals("super-strong-random-value", storage.secret())

        // New instance reads back the same file
        val storage2 = PluginSettingsStorage(stubServerPaths(tmp))
        assertEquals("super-strong-random-value", storage2.secret())
    }

    @Test
    fun `setSecret with blank removes the key`(@TempDir tmp: Path) {
        val storage = PluginSettingsStorage(stubServerPaths(tmp))
        storage.setSecret("a-value")
        storage.setSecret("   ")
        assertNull(storage.secret())
    }

    @Test
    fun `clearSecret removes the key`(@TempDir tmp: Path) {
        val storage = PluginSettingsStorage(stubServerPaths(tmp))
        storage.setSecret("a-value")
        storage.clearSecret()
        assertNull(storage.secret())
    }

    @Test
    fun `file lives in the config dir under the expected name`(@TempDir tmp: Path) {
        val storage = PluginSettingsStorage(stubServerPaths(tmp))
        storage.setSecret("x")
        assertTrue(storage.file().exists())
        assertEquals("teamcity-github-bridge.properties", storage.file().name)
        assertEquals(tmp.resolve("config").toFile().absolutePath, storage.file().parentFile.absolutePath)
    }

    @Test
    fun `secret survives concurrent writes`(@TempDir tmp: Path) {
        val storage = PluginSettingsStorage(stubServerPaths(tmp))
        val threads = (1..8).map { t ->
            Thread {
                repeat(20) { i ->
                    storage.setSecret("t$t-i$i")
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        // The final value is one of the writes; we don't care which,
        // just that the file is not corrupted and a value survives.
        assertTrue(storage.secret()!!.startsWith("t"))
    }

    private fun stubServerPaths(tmp: Path): ServerPaths {
        val data = tmp.toFile()
        File(data, "config").mkdirs()
        File(data, "logs").mkdirs()
        return ServerPaths(data.absolutePath)
    }
}
