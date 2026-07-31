package io.github.dlachouette.teamcity.github.feature

import jetbrains.buildServer.serverSide.SBuildType

// Detects build configurations that carry BOTH this plugin's feature and
// TeamCity's bundled *Commit status publisher*.
//
// Two status producers on one build means two competing rows per build on
// GitHub — a rich Check Run from the bridge and a generic
// "TeamCity build finished" Commit Status from TeamCity — and branch
// protection can end up requiring the wrong one.
//
// The plugin **warns and stops there**: which system reports to GitHub is a
// configuration decision that belongs to the operator, and silently disabling
// another plugin's output would be worse than a duplicate row. See
// the 1.9.0 CHANGELOG entry.
object BundledPublisherDetector {

    // The bundled feature's type id is `commit-status-publisher`, but it lives
    // in a bundled plugin the SDK does not expose, so it cannot be referenced
    // as a constant. Match on the shape of the id instead of an exact string:
    // it survives a rename, and a third-party feature that *is* a commit
    // status publisher is a genuine conflict too.
    fun isBundledPublisher(featureType: String): Boolean {
        val t = featureType.lowercase()
        return t.contains("commit") && t.contains("status")
    }

    // Pure form, for tests: does this set of resolved feature type ids hold
    // both producers?
    fun conflicts(featureTypes: Collection<String>): Boolean =
        featureTypes.any { it == GitHubBridgeBuildFeature.FEATURE_TYPE } &&
            featureTypes.any { isBundledPublisher(it) }

    // Opted-in build configurations that also carry the bundled publisher.
    // Read through `resolvedSettings` like `BridgeFeatureReader`, so a feature
    // inherited from a BuildType template counts on both sides — that case is
    // the easiest one to miss by eye.
    fun scan(buildTypes: Collection<SBuildType>): List<String> =
        buildTypes.filter { bt ->
            BridgeFeatureReader.read(bt) != null &&
                bt.resolvedSettings.buildFeatures.any { isBundledPublisher(it.type) }
        }.map { it.externalId }
}
