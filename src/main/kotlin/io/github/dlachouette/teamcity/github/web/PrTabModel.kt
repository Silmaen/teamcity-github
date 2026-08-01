package io.github.dlachouette.teamcity.github.web

import io.github.dlachouette.teamcity.github.enrich.PrParameterProvider

// What the build page's "Pull request" tab shows, built from the parameters the
// build already carries.
//
// Reading the build's own parameters rather than calling GitHub is the whole
// design of this tab:
//
//   - **no API call** to render a page — a build page is opened far more often
//     than a build runs, and a tab that cost a round trip per view would be a
//     rate-limit problem disguised as a feature;
//   - **it shows what the build saw.** The values were resolved when the build
//     ran; if the pull request has been retitled or merged since, the tab still
//     describes the run rather than the present, which is what someone looking
//     at an old build wants;
//   - it works for a **finished** build for ever, with no dependency on the pull
//     request still existing.
//
// The price is that a build from before these parameters existed shows less. The
// tab degrades field by field rather than refusing to render.
data class PrTabModel(
    val number: Int,
    val url: String,
    val title: String,
    val author: String,
    val draft: Boolean,
    val sourceBranch: String,
    val targetBranch: String,
    val headSha: String,
    val baseSha: String,
    val mergeBase: String,
    val changedFiles: String,
    val additions: String,
    val deletions: String,
    val commits: String,
    val labels: List<String>,
) {
    // The range a diff-scoped step must use. Empty when the merge base is
    // unknown, because the alternative — falling back to the base branch's head
    // — silently widens the diff to everything that landed on the base since,
    // and a wrong range is worse than a missing one.
    val diffRange: String
        get() = if (mergeBase.isBlank() || headSha.isBlank()) "" else "$mergeBase..$headSha"

    // --- derived links ---
    //
    // Everything below is string surgery on `url`, which is why the tab can
    // offer a page of links without a single API call and without a second
    // parameter to carry a hostname. It also means they all appear and disappear
    // together: no `url`, no links, and never a link built from a guessed host.
    //
    // The repository's web root: `https://host/owner/repo` out of
    // `https://host/owner/repo/pull/6`. Blank when the URL is absent or does not
    // have the shape we expect — a GitHub URL we do not recognise is not one to
    // build other URLs from.
    val repoWebRoot: String
        get() = if (url.contains("/pull/")) url.substringBefore("/pull/") else ""

    // The pull request's own tabs.
    val checksUrl: String get() = if (url.isBlank()) "" else "$url/checks"
    val filesUrl: String get() = if (url.isBlank()) "" else "$url/files"
    val commitsUrl: String get() = if (url.isBlank()) "" else "$url/commits"

    // The commit this build actually judged, on GitHub.
    val headCommitUrl: String
        get() = if (repoWebRoot.isBlank() || headSha.isBlank()) "" else "$repoWebRoot/commit/$headSha"

    // The change itself, rendered by GitHub from the same range a diff-scoped
    // build step should use. The most direct answer to "what is in this pull
    // request" that exists.
    val compareUrl: String
        get() = if (repoWebRoot.isBlank() || diffRange.isBlank()) ""
        else "$repoWebRoot/compare/${mergeBase}...${headSha}"

    companion object {
        // Null when this build is not a pull-request build, which is what makes
        // the tab absent rather than empty on a build of `main`.
        //
        // The number is the only mandatory field: everything else is decoration
        // and renders blank when a build predates the parameter that carries it.
        fun from(params: (String) -> String?): PrTabModel? {
            fun p(key: String): String = params(key)?.trim().orEmpty()

            if (p(PrParameterProvider.PARAM_IS_PULL_REQUEST) != "true") return null
            val number = p(PrParameterProvider.PARAM_PR_NUMBER).toIntOrNull()?.takeIf { it > 0 } ?: return null

            return PrTabModel(
                number = number,
                url = p(PrParameterProvider.PARAM_PR_URL),
                title = p(PrParameterProvider.PARAM_PR_TITLE),
                author = p(PrParameterProvider.PARAM_PR_AUTHOR),
                draft = p(PrParameterProvider.PARAM_IS_DRAFT) == "true",
                sourceBranch = p(PrParameterProvider.PARAM_PR_SOURCE_BRANCH),
                targetBranch = p(PrParameterProvider.PARAM_PR_TARGET_BRANCH),
                headSha = p(PrParameterProvider.PARAM_PR_HEAD_SHA),
                baseSha = p(PrParameterProvider.PARAM_PR_BASE_SHA),
                mergeBase = p(PrParameterProvider.PARAM_PR_MERGE_BASE),
                changedFiles = p(PrParameterProvider.PARAM_PR_CHANGED_FILES),
                additions = p(PrParameterProvider.PARAM_PR_ADDITIONS),
                deletions = p(PrParameterProvider.PARAM_PR_DELETIONS),
                commits = p(PrParameterProvider.PARAM_PR_COMMITS),
                labels = p(PrParameterProvider.PARAM_PR_LABELS)
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() },
            )
        }
    }
}
