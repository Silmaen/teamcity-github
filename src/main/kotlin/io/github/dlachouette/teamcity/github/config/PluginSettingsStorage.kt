package io.github.dlachouette.teamcity.github.config

import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.serverSide.ServerPaths
import java.io.File
import java.util.Properties

// Plugin-owned settings file under <TC_DATA_DIR>/config/. Holds values
// that the admin sets from the in-product page, kept separately from
// TC's internal.properties so we never mutate operator-owned files.
//
// Generic key/value store now (webhook secret + server tuning + feature
// flags). Read on every access (no caching) — the file is tiny and reads
// happen at most per webhook delivery / build event.
//
// All read-modify-write cycles are serialized on a single lock so that
// concurrent saves cannot race on the temp file (the previous code threw
// NoSuchFileException on the .tmp under concurrent writers).
class PluginSettingsStorage(serverPaths: ServerPaths) {

    private val file: File = File(serverPaths.configDir, FILE_NAME)
    private val lock = Any()

    fun file(): File = file

    // ----- generic access -----

    fun get(key: String): String? = synchronized(lock) {
        load().getProperty(key)?.takeIf { it.isNotBlank() }
    }

    // Sets `key` to `value`, or removes it when `value` is blank. Returns
    // true if the stored content changed.
    fun set(key: String, value: String): Boolean = synchronized(lock) {
        val props = load()
        val changed = if (value.isBlank()) {
            props.remove(key) != null
        } else {
            props.setProperty(key, value) != value
        }
        if (changed) save(props)
        changed
    }

    // ----- webhook secret (kept as named API for call sites) -----

    fun secret(): String? = get(KEY_SECRET)

    fun setSecret(value: String) {
        set(KEY_SECRET, value)
    }

    fun clearSecret() {
        set(KEY_SECRET, "")
    }

    private fun load(): Properties {
        val props = Properties()
        if (file.exists()) {
            try {
                file.inputStream().use { props.load(it) }
            } catch (e: Exception) {
                LOG.warn("Failed reading $file: ${e.message}", e)
            }
        }
        return props
    }

    private fun save(props: Properties) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.outputStream().use { props.store(it, "teamcity-github-bridge settings - managed by the admin page; do not edit by hand while TC is running.") }
            if (!tmp.renameTo(file)) {
                // Fallback on non-atomic rename (rare on POSIX, can happen on Windows).
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            LOG.warn("Failed writing $file: ${e.message}", e)
            throw e
        }
    }

    companion object {
        const val FILE_NAME: String = "teamcity-github-bridge.properties"
        const val KEY_SECRET: String = "webhook.secret"
        private val LOG = Logger.getInstance(PluginSettingsStorage::class.java.name)
    }
}
