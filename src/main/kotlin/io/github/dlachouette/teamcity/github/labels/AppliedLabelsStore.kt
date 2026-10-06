package io.github.dlachouette.teamcity.github.labels

import com.intellij.openapi.diagnostic.Logger
import java.io.File

// The labels the bridge added to each open pull request.
//
// It is what lets a human's removal stick: a rule that still holds would put the
// label straight back on the next push, so a label the bridge added once and no
// longer finds on the pull request is never added again. Forgotten when the
// pull request closes. Kept on disk (`<TC_DATA_DIR>/system/pluginData/…`) so a
// restart does not re-add what someone removed.
class AppliedLabelsStore(private val file: File) {

    private val applied = mutableMapOf<String, MutableSet<String>>()
    private val lock = Any()

    init {
        load()
    }

    fun appliedTo(repo: String, pr: Int): Set<String> = synchronized(lock) {
        applied[key(repo, pr)]?.toSet().orEmpty()
    }

    fun record(repo: String, pr: Int, labels: Collection<String>) = synchronized(lock) {
        if (labels.isEmpty()) return@synchronized
        applied.getOrPut(key(repo, pr)) { mutableSetOf() } += labels
        save()
    }

    fun forget(repo: String, pr: Int) = synchronized(lock) {
        if (applied.remove(key(repo, pr)) != null) save()
    }

    private fun key(repo: String, pr: Int) = "${repo.lowercase()}#$pr"

    private fun load() {
        if (!file.exists()) return
        try {
            file.readLines().forEach { line ->
                val f = line.split('\t')
                if (f.size == 2 && f[0].isNotBlank() && f[1].isNotBlank()) applied.getOrPut(f[0]) { mutableSetOf() } += f[1]
            }
        } catch (e: Exception) {
            LOG.warn("Could not read applied labels from $file: ${e.message}")
        }
    }

    private fun save() {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(applied.entries.joinToString("") { (k, labels) -> labels.joinToString("") { "$k\t$it\n" } })
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            LOG.warn("Could not write applied labels to $file: ${e.message}")
        }
    }

    companion object {
        private val LOG = Logger.getInstance(AppliedLabelsStore::class.java.name)

        const val FILE_NAME: String = "applied-labels.tsv"
    }
}
