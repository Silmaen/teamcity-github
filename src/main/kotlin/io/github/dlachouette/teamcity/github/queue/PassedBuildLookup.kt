package io.github.dlachouette.teamcity.github.queue

import jetbrains.buildServer.serverSide.SBuildType
import jetbrains.buildServer.serverSide.SFinishedBuild

// "Has this exact commit already passed in this build configuration?"
//
// Two sites ask it and must agree: the cleaner (`skipIfCommitPassed` drops the
// queued duplicate) and the Check Run publisher (a chain's duplicate must not
// turn that green row back to "Queued").
//
// Same build configuration, same commit, any ref: GitHub keys a Check Run on
// (name, sha), so two refs of one commit are one row anyway. A personal build
// is never that evidence: it passed on a patch that is not in the repository,
// and it published nothing.
object PassedBuildLookup {

    // How far back to look for a successful build of the same commit.
    const val HISTORY_SCAN_DEPTH: Int = 50

    fun find(buildType: SBuildType, headSha: String, excludeBuildId: Long? = null): SFinishedBuild? =
        buildType.history.asSequence()
            .take(HISTORY_SCAN_DEPTH)
            .firstOrNull { build ->
                build.buildId != excludeBuildId &&
                    !build.buildPromotion.isPersonal &&
                    build.canceledInfo == null &&
                    build.buildStatus.isSuccessful &&
                    build.revisions.any { it.revision == headSha }
            }
}
