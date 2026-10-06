package io.github.dlachouette.teamcity.github.report

import io.github.dlachouette.teamcity.github.feature.BridgeFeatureReader
import jetbrains.buildServer.serverSide.SBuildType

// Detects build configurations that publish under the same Check Run name to
// the same repository.
//
// GitHub keys a row on `(name, head_sha)`, so two such configurations
// overwrite each other's row on every shared commit, and the one a branch
// protection rule sees is whichever finished last. A fixed `checkName` makes
// this easy to cause by copy-paste, and `checkName.stripPrefix` can too.
//
// Like `BundledPublisherDetector`, this warns and stops there.
object CheckNameCollisionDetector {

    data class Publisher(val repo: String, val checkName: String, val buildType: String)

    data class Collision(val repo: String, val checkName: String, val buildTypes: List<String>)

    // Pure form, for tests. Repository slugs compare without case, as GitHub
    // does; check names are compared exactly, as GitHub stores them.
    fun find(publishers: Collection<Publisher>): List<Collision> = publishers
        .groupBy { it.repo.lowercase() to it.checkName }
        .filterValues { it.size > 1 }
        .map { (_, group) -> Collision(group.first().repo, group.first().checkName, group.map { it.buildType }.sorted()) }
        .sortedWith(compareBy({ it.repo }, { it.checkName }))

    fun scan(buildTypes: Collection<SBuildType>): List<Collision> = find(publishers(buildTypes))

    // Every name the bridge will post, and where. Only configurations that
    // actually publish: one with `publishChecks=false` posts nothing, so it can
    // neither collide nor satisfy a required check.
    fun publishers(buildTypes: Collection<SBuildType>): List<Publisher> =
        buildTypes.mapNotNull { bt ->
            val config = BridgeFeatureReader.read(bt)?.takeIf { it.publishChecks } ?: return@mapNotNull null
            Publisher(config.repo.slug, checkRunName(bt), bt.externalId)
        }
}
