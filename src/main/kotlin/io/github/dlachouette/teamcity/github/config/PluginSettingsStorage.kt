package io.github.dlachouette.teamcity.github.config

import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.serverSide.ServerPaths
import java.io.File
import java.util.Properties

// Plugin-owned settings file under <TC_DATA_DIR>/config/. Holds values
// that the admin sets from the in-product page, kept separately from
// TC's internal.properties so we never mutate operator-owned files.
//
// Currently a single key: webhook.secret. Read on every access (no
// caching) - the file is tiny and read happens at most per webhook
// delivery.
class PluginSettingsStorage(serverPaths: ServerPaths) {

    private val file: File = File(serverPaths.configDir, FILE_NAME)

    fun file(): File = file

    fun secret(): String? = load().getProperty(KEY_SECRET)?.takeIf { it.isNotBlank() }

    fun setSecret(value: String) {
        val props = load()
        if (value.isBlank()) {
            props.remove(KEY_SECRET)
        } else {
            props.setProperty(KEY_SECRET, value)
        }
        save(props)
    }

    fun clearSecret() {
        val props = load()
        if (props.remove(KEY_SECRET) != null) save(props)
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
