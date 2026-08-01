package io.github.dlachouette.teamcity.github.api

import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.ConcurrentHashMap

class PrInfoCache(
    private val gitHubClient: GitHubClient,
) {
    private data class Key(val repo: RepoCoords, val number: Int)
    private data class Entry(val info: PrInfo, val fetchedAtMs: Long)

    // Commit-keyed side store for `getForCommit`. Its entry holds a
    // NULLABLE PrInfo on purpose: "this commit belongs to no open PR" is
    // the common answer for branch builds and must be cached too,
    // otherwise every build of every opted-in BuildType on a plain branch
    // would ask GitHub again.
    private data class CommitKey(val repo: RepoCoords, val sha: String)
    private data class CommitEntry(val info: PrInfo?, val fetchedAtMs: Long)

    private val store = ConcurrentHashMap<Key, Entry>()
    private val commitStore = ConcurrentHashMap<CommitKey, CommitEntry>()

    var ttlMs: Long = DEFAULT_TTL_MS
    // How long PAST the TTL a stale entry may still be served when a
    // refresh fetch fails. Bounds the previous behaviour where a single
    // fetch failure pinned the stale entry forever — a PR that went
    // draft->ready (or got a new head SHA) would otherwise be reported
    // with stale state indefinitely, making the gate decide wrongly.
    var staleGraceMs: Long = DEFAULT_STALE_GRACE_MS
    var clock: () -> Long = { System.currentTimeMillis() }

    // Whether to resolve the merge base when filling the cache. Pushed in from
    // `BridgeServerSettings.applyTo`, like the TTL and the grace window, so a
    // change on the admin page takes effect without a restart.
    var mergeBaseEnabled: Boolean = true

    fun get(
        repo: RepoCoords,
        number: Int,
        accessToken: String,
        apiBase: String = GitHubClient.DEFAULT_API_BASE,
    ): PrInfo? {
        val key = Key(repo, number)
        val now = clock()
        val cached = store[key]
        if (cached != null && now - cached.fetchedAtMs < ttlMs) {
            return cached.info
        }

        val fresh = gitHubClient.getPr(accessToken, repo, number, apiBase)
            ?.let { enrich(it, repo, accessToken, apiBase) }
        if (fresh != null) {
            store[key] = Entry(fresh, now)
            return fresh
        }

        // Refresh failed (network blip, transient 5xx, rate-limit). Serve
        // the stale entry only inside the grace window; beyond it, drop it
        // and report "unknown" rather than acting on arbitrarily-old state.
        if (cached != null) {
            val age = now - cached.fetchedAtMs
            if (age <= ttlMs + staleGraceMs) {
                LOG.debug("Serving stale PR ${repo.slug}#$number (age=${age}ms) after refresh failure")
                return cached.info
            }
            LOG.warn("Dropping stale PR ${repo.slug}#$number (age=${age}ms exceeds grace) after refresh failure")
            store.remove(key, cached)
        }
        return null
    }

    // The open PR whose HEAD is `sha`, or null when there is none.
    //
    // This is the branch-build counterpart of `get`: a build launched on a
    // plain branch ref carries no PR number, so the PR is looked up from
    // the commit. Unlike `get` there is no stale-grace window — a failed
    // fetch is indistinguishable from "no PR" at the API level, so the
    // answer is simply cached for one TTL and re-asked afterwards.
    fun getForCommit(
        repo: RepoCoords,
        sha: String,
        accessToken: String,
        apiBase: String = GitHubClient.DEFAULT_API_BASE,
    ): PrInfo? {
        if (sha.isBlank()) return null
        val key = CommitKey(repo, sha)
        val now = clock()
        val cached = commitStore[key]
        if (cached != null && now - cached.fetchedAtMs < ttlMs) {
            return cached.info
        }

        val selected = selectForCommit(gitHubClient.listPrsForCommit(accessToken, repo, sha, apiBase), sha)
            ?.let { enrich(it, repo, accessToken, apiBase) }
        commitStore[key] = CommitEntry(selected, now)
        if (selected != null) {
            // Seed the number-keyed store so a follow-up get(number) for
            // the same PR (gate, comment) costs no extra API call.
            store[Key(repo, selected.number)] = Entry(selected, now)
            LOG.debug("Commit ${repo.slug}@$sha resolved to open PR #${selected.number} (${selected.headRef})")
        } else {
            LOG.debug("Commit ${repo.slug}@$sha is the head of no open PR")
        }
        return selected
    }

    fun invalidate(repo: RepoCoords, number: Int) {
        store.remove(Key(repo, number))
    }

    // Fill in what only a compare call knows: where the branches diverged, and —
    // for a pull request resolved from a commit, where GitHub omits them — how
    // big the change is.
    //
    // Done here rather than in the client so it happens **once per cache fill**
    // instead of once per read, and applied on **both** fetch paths. Missing it on
    // the commit path was a real bug: a branch-source build resolves its pull
    // request from the commit, so exactly the builds that most need the merge base
    // were the ones never getting it.
    private fun enrich(
        pr: PrInfo,
        repo: RepoCoords,
        accessToken: String,
        apiBase: String,
    ): PrInfo {
        if (!mergeBaseEnabled) return pr
        if (pr.baseRef.isBlank() || pr.headSha.isBlank()) return pr
        val cmp = try {
            gitHubClient.compare(accessToken, repo, pr.baseRef, pr.headSha, apiBase)
        } catch (e: Exception) {
            LOG.debug("Compare lookup failed for ${repo.slug}#${pr.number}: ${e.message}")
            null
        } ?: return pr
        return pr.copy(
            mergeBaseSha = cmp.mergeBaseSha,
            changedFileNames = cmp.files,
            changedFilesTruncated = cmp.filesTruncated,
            // Only fill a count the pull request object left at zero, and only
            // when the compare response actually knew it. Never overwrite a
            // number GitHub already gave us with a possibly-truncated one.
            changedFiles = if (pr.changedFiles > 0) pr.changedFiles else cmp.changedFiles,
            commits = if (pr.commits > 0) pr.commits else cmp.commits,
        )
    }

    fun size(): Int = store.size

    // Drop every entry, and say how many. Used by the admin page's "clear cached
    // tokens" action: a token minted under new permissions may well see a
    // different pull request (a private repo the App could not read before), so
    // dropping the tokens without dropping what they fetched would keep serving
    // the older, thinner answer for the rest of the TTL.
    fun clear(): Int {
        val size = store.size
        store.clear()
        return size
    }

    companion object {
        private val LOG = Logger.getInstance(PrInfoCache::class.java.name)
        const val DEFAULT_TTL_MS: Long = 60_000L
        // Five minutes of grace past the TTL for stale-on-failure serving.
        const val DEFAULT_STALE_GRACE_MS: Long = 5L * 60_000L

        // Which of the PRs GitHub reports for a commit a branch build may
        // claim to be building. Pure, so it is unit-tested without HTTP.
        //
        //   - OPEN only: `GET /commits/{sha}/pulls` also lists merged and
        //     closed PRs, and a build of `main` after a rebase-merge would
        //     otherwise get labelled with the PR that was merged.
        //   - head only: the commit must BE the PR's head. A build of an
        //     intermediate commit is not the PR's current state, and
        //     reporting it as such would attach a Check Run / comment to a
        //     PR the build does not represent.
        //   - lowest number wins when a commit heads several open PRs, so
        //     repeated builds of the same commit always pick the same PR.
        fun selectForCommit(prs: List<PrInfo>, sha: String): PrInfo? =
            prs.filter { it.state.equals("open", ignoreCase = true) && it.headSha.equals(sha, ignoreCase = true) }
                .minByOrNull { it.number }
    }
}
