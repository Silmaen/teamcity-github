package io.github.dlachouette.teamcity.github.report

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.util.concurrent.ConcurrentHashMap

// The Check Runs the bridge left open on GitHub — posted `queued` or
// `in_progress` and not concluded yet — keyed by build promotion.
//
// It is how a row that never got its conclusion is found again: the server
// stopped between start and finish, or a finish / queue-exit event was missed.
// A required check left open blocks the merge until a human re-runs it, which
// is the one failure of the design that needed cleaning by hand. The publisher
// reconciles these entries against TeamCity at startup and periodically.
//
// Kept on disk (`<TC_DATA_DIR>/system/pluginData/…`) so it survives a restart,
// which is precisely when it is needed. It is a cache, not settings: losing it
// only loses the repair, and nothing else reads it.
class OpenCheckRunRegistry(
    private val file: File,
    private val now: () -> Long = System::currentTimeMillis,
) {

    data class Entry(
        val promotionId: Long,
        val buildTypeExternalId: String,
        val headSha: String,
        val openedAt: Long,
    )

    private val entries = ConcurrentHashMap<Long, Entry>()

    @Volatile
    private var dirty = false

    // Re-opening keeps the first `openedAt`: queued then in_progress is one row.
    fun open(promotionId: Long, buildTypeExternalId: String, headSha: String) {
        entries.compute(promotionId) { _, old ->
            Entry(promotionId, buildTypeExternalId, headSha, old?.openedAt ?: now())
        }
        dirty = true
    }

    fun close(promotionId: Long) {
        if (entries.remove(promotionId) != null) dirty = true
    }

    fun snapshot(): List<Entry> = entries.values.sortedBy { it.openedAt }

    fun size(): Int = entries.size

    fun load() {
        if (!file.exists()) return
        try {
            file.readLines().mapNotNull(::parse).forEach { entries[it.promotionId] = it }
        } catch (e: Exception) {
            LOG.warn("Could not read open Check Runs from $file: ${e.message}")
        }
    }

    // Written whole and renamed into place, only when something changed.
    fun flush() {
        if (!dirty) return
        dirty = false
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(snapshot().joinToString("") { "${it.promotionId}\t${it.buildTypeExternalId}\t${it.headSha}\t${it.openedAt}\n" })
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            dirty = true
            LOG.warn("Could not write open Check Runs to $file: ${e.message}")
        }
    }

    private fun parse(line: String): Entry? {
        val f = line.split('\t')
        if (f.size != 4) return null
        val id = f[0].toLongOrNull() ?: return null
        val at = f[3].toLongOrNull() ?: return null
        if (f[1].isBlank() || f[2].isBlank()) return null
        return Entry(id, f[1], f[2], at)
    }

    companion object {
        private val LOG = Logger.getInstance(OpenCheckRunRegistry::class.java.name)

        const val FILE_NAME: String = "open-check-runs.tsv"
    }
}
