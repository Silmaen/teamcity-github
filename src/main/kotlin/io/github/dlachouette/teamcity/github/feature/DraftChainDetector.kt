package io.github.dlachouette.teamcity.github.feature

import jetbrains.buildServer.serverSide.SBuildType

// Detects composite build configurations that run on draft pull requests
// while part of their snapshot chain is set to skip drafts.
//
// A composite drags its whole chain into the queue: when it runs on a draft,
// every dependency builds too, whatever that dependency's own
// `triggerOnPrDraft` says — and its "Skipped: draft PR" row, which reviewers
// rely on to see what was held back, never appears. Usually the operator
// meant one of the two settings, not both.
//
// Like `BundledPublisherDetector`, this warns and stops there.
object DraftChainDetector {

    data class Mismatch(val composite: String, val skippingDependencies: List<String>)

    // Pure form, for tests. `runsOnDrafts` is null for a configuration the
    // bridge does not gate (not opted in): such a dependency has no draft
    // setting to contradict. The chain is walked transitively, since a
    // nested composite pulls its own dependencies in as well.
    fun <T> find(
        buildTypes: Collection<T>,
        id: (T) -> String,
        isComposite: (T) -> Boolean,
        runsOnDrafts: (T) -> Boolean?,
        dependencies: (T) -> List<T>,
    ): List<Mismatch> = buildTypes
        .filter { isComposite(it) && runsOnDrafts(it) == true }
        .mapNotNull { composite ->
            val seen = mutableSetOf(id(composite))
            val skipping = mutableListOf<String>()
            val pending = ArrayDeque(dependencies(composite))
            while (pending.isNotEmpty()) {
                val dep = pending.removeFirst()
                if (!seen.add(id(dep))) continue
                if (runsOnDrafts(dep) == false) skipping += id(dep)
                pending += dependencies(dep)
            }
            skipping.takeIf { it.isNotEmpty() }?.let { Mismatch(id(composite), it.sorted()) }
        }
        .sortedBy { it.composite }

    fun scan(buildTypes: Collection<SBuildType>): List<Mismatch> = find(
        buildTypes,
        id = { it.externalId },
        isComposite = { it.isCompositeBuildType },
        runsOnDrafts = { BridgeFeatureReader.read(it)?.triggerOnPrDraft },
        // A dependency the current user cannot see throws; skip it.
        dependencies = { bt -> bt.dependencies.mapNotNull { runCatching { it.dependOn }.getOrNull() } },
    )
}
