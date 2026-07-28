package io.github.dlachouette.teamcity.github.api

data class PrInfo(
    val number: Int,
    val title: String,
    val author: String,
    val headRef: String,
    val baseRef: String,
    val headSha: String,
    val draft: Boolean,
    val state: String,
    // PR description body and label names — used by the metadata gate
    // (title/body phrase filters + label filter). Default empty so
    // existing constructions/tests need not supply them.
    val body: String = "",
    val labels: List<String> = emptyList(),
    // `owner/name` of the repository the head branch lives in. Equal to the
    // base repository for an ordinary PR; different for a fork; blank when
    // GitHub omits it (a deleted fork). Used by the fork guard — the bridge
    // is attached to one repository, never to its forks.
    val headRepo: String = "",
)

data class RepoCoords(val owner: String, val name: String) {
    val slug: String get() = "$owner/$name"

    companion object {
        fun parse(slug: String): RepoCoords {
            val parts = slug.split('/', limit = 2)
            require(parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) {
                "Invalid repository slug: '$slug', expected 'owner/name'"
            }
            return RepoCoords(parts[0], parts[1])
        }
    }
}
