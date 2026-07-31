package io.github.dlachouette.teamcity.github.report

import jetbrains.buildServer.messages.ErrorData

// Why the build is not green — "your code is broken" or "our CI broke".
//
// Everything that is not successful used to become `failure`, so a lost
// checkout, an unresolvable artifact dependency or a runner that could not
// start turned a pull request red exactly like a failing test. That is the
// difference between fixing a commit and re-running a build, and a reviewer
// could not tell one from the other.
enum class FailureKind {
    // The build ran and its own steps failed: a failing test, a compile
    // error, a non-zero exit code, a failure condition. The PR's problem.
    CODE,

    // The build could not run, or could not run properly, for a reason
    // outside the repository: sources, agent, runner, external dependency.
    // Nobody learns anything about the commit from this.
    INFRASTRUCTURE,

    // A snapshot dependency failed, so this build never ran. Deliberately
    // NOT infrastructural even though TeamCity groups it with its internal
    // errors: the dependency may well have failed on this very PR's code,
    // and calling that a CI hiccup would unblock a merge it should block.
    // It gets a name, not a different conclusion.
    DEPENDENCY,
}

// The kind, plus TeamCity's own label for the problem that decided it
// ("Unable to collect changes", "Artifacts resolving failed"), which is what
// makes the Check Run title say something a reviewer can act on.
data class FailureClassification(
    val kind: FailureKind,
    val cause: String? = null,
) {
    val infrastructural: Boolean get() = kind == FailureKind.INFRASTRUCTURE
}

// Pure classification of a failed build, from the *types* of the build
// problems it reported (`BuildProblemData.getType()`). No SDK fixture needed:
// the publisher reads the types, this decides what they mean.
object FailureClassifier {

    // `ErrorData.isInternalError` is TeamCity's own answer to "is this the
    // user's code or ours" — the set behind it is exactly the infrastructural
    // types (UPDATE_SOURCES, CHECKING_FOR_CHANGES_ERROR,
    // ARTIFACT_DEPENDENCY_ERROR, PREPARATION_FAILURE, BUILD_RUNNER_ERROR,
    // INACCESSIBLE_EXTERNAL_DEPENDENCY, …). Asking the SDK rather than
    // hardcoding that list means a type added by a future TeamCity is
    // classified with it, not against it.
    fun classify(problemTypes: List<String>): FailureClassification {
        val types = problemTypes.filter { it.isNotBlank() }
        if (types.isEmpty()) return FailureClassification(FailureKind.CODE)

        // One problem the build itself produced makes this the code's failure,
        // whatever else also broke. The safe direction: a build that both
        // failed its tests and hiccuped on an artifact download stays red.
        if (types.any { !isInternal(it) }) return FailureClassification(FailureKind.CODE)

        // All internal. A dependency failure among them is not a cause of its
        // own unless it is the only thing that went wrong.
        val infra = types.firstOrNull { !isSnapshotDependency(it) }
        return if (infra != null) FailureClassification(FailureKind.INFRASTRUCTURE, labelOf(infra))
        else FailureClassification(FailureKind.DEPENDENCY, labelOf(types.first()))
    }

    private fun isInternal(type: String): Boolean = try {
        ErrorData.isInternalError(type)
    } catch (e: Exception) {
        false
    }

    private fun isSnapshotDependency(type: String): Boolean = try {
        ErrorData.isSnapshotDependencyError(type)
    } catch (e: Exception) {
        false
    }

    // TeamCity's human-readable label for a problem type, so the Check Run
    // says what the TeamCity page says. A type the map does not cover (a
    // future one) is humanised from its constant rather than dropped:
    // `CHECKING_FOR_CHANGES_ERROR` -> "Checking for changes error".
    fun labelOf(type: String): String = DESCRIPTIONS[type] ?: humanise(type)

    private fun humanise(type: String): String {
        val words = type.removeSuffix("_TYPE").replace('_', ' ').trim().lowercase()
        if (words.isEmpty()) return type
        return words.replaceFirstChar { it.uppercase() }
    }

    // `ErrorData.TYPE_DESCRIPTIONS` is declared as a raw `java.util.Map`, so
    // it is read entry by entry rather than cast: a non-String value would
    // otherwise only blow up at lookup time, inside a build listener.
    private val DESCRIPTIONS: Map<String, String> by lazy {
        try {
            val raw = ErrorData.TYPE_DESCRIPTIONS ?: return@lazy emptyMap()
            raw.entries.mapNotNull { entry ->
                val key = entry.key as? String ?: return@mapNotNull null
                val value = entry.value as? String ?: return@mapNotNull null
                key to value
            }.toMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
