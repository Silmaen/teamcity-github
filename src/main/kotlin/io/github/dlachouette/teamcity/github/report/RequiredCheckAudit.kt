package io.github.dlachouette.teamcity.github.report

// Compares the check names a repository's branches require with the names the
// bridge will ever post there.
//
// A required name nothing posts blocks every pull request for ever, silently:
// GitHub just waits. It is created without touching anything called "GitHub" —
// rename a configuration, move it in the project tree, set
// `checkName.stripPrefix` — so the plugin is the one place that can see it.
//
// A required name may legitimately come from elsewhere (GitHub Actions, another
// CI); the audit cannot tell, so it names the gap and lets the operator judge.
object RequiredCheckAudit {

    data class Result(
        // required name -> the branches requiring it, for names nothing here posts
        val missing: Map<String, List<String>>,
        // names the bridge posts that no branch requires (informational)
        val unrequired: List<String>,
    )

    fun compare(requiredByBranch: Map<String, Set<String>>, produced: Set<String>): Result {
        val missing = sortedMapOf<String, MutableList<String>>()
        requiredByBranch.forEach { (branch, names) ->
            names.filterNot { it in produced }.forEach { missing.getOrPut(it) { mutableListOf() } += branch }
        }
        val required = requiredByBranch.values.flatten().toSet()
        return Result(missing.mapValues { it.value.sorted() }, produced.filterNot { it in required }.sorted())
    }
}
