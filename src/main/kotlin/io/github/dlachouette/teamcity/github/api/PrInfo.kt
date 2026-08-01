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

    // --- what the pull request *is*, straight out of the payload we already
    // --- have. Every one of these is free: GitHub sends them on the
    // --- `pull_request` event and returns them from `GET /pulls/{n}`, so
    // --- publishing them costs no call. Defaults keep every existing
    // --- construction and test valid.

    // The PR's page on GitHub. The one field that makes a build in TeamCity
    // able to point back at what it is judging.
    val htmlUrl: String = "",

    // The base branch's head at the time of the event — NOT where the branches
    // diverged. For "what did this PR change", the divergence point is
    // `mergeBaseSha` below; using this one instead makes a diff that also
    // contains everything that landed on the base since.
    val baseSha: String = "",

    // How big the change is. `changedFiles` is what a build step wants for a
    // "did this touch much?" gate without carrying a file list around.
    val changedFiles: Int = 0,
    val additions: Int = 0,
    val deletions: Int = 0,
    val commits: Int = 0,

    // Where the branches diverged: `merge_base_commit.sha` from
    // `GET /compare/{base}...{head}`. **Not** free — one extra call — so it is
    // null unless `mergeBase.enabled` is on and the call succeeded, and it is
    // cached with the rest of this object.
    val mergeBaseSha: String? = null,

    // The files this pull request changes, from the same compare response as the
    // merge base — so the list costs nothing beyond it.
    //
    // Deliberately **not** published as a build parameter: a parameter holding
    // hundreds of paths is a liability (it reaches every agent, every log, every
    // UI that prints parameters). It lives here, in the cache, and the build
    // page's "Pull request" tab is what renders it.
    val changedFileNames: List<String> = emptyList(),
    val changedFilesTruncated: Boolean = false,
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

// What `GET /compare/{base}...{head}` tells us about a pull request's change.
//
// One call answers three questions the pull request object does not: where the
// branches diverged, and — for a PR resolved from a commit rather than by number,
// where GitHub omits the counts — how big the change is.
data class CompareInfo(
    val mergeBaseSha: String,
    // 0 when unknown: either GitHub sent no files, or it truncated the array and
    // the real count cannot be known from this response.
    val changedFiles: Int = 0,
    val commits: Int = 0,
    val files: List<String> = emptyList(),
    // GitHub capped the array. The names we have are still real; the list is just
    // not all of them, and anything counting it would be wrong.
    val filesTruncated: Boolean = false,
)
