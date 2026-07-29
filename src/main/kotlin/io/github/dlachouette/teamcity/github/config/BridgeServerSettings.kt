package io.github.dlachouette.teamcity.github.config

import com.intellij.openapi.diagnostic.Logger
import io.github.dlachouette.teamcity.github.api.GitHubClient
import io.github.dlachouette.teamcity.github.cache.PrInfoCache
import jetbrains.buildServer.serverSide.TeamCityProperties

// Single typed accessor for every server-global tuning value and feature
// flag. Resolution order for each key:
//   1. the plugin-owned settings file (set from the admin page),
//   2. the legacy `teamcity.github.bridge.*` internal property
//      (the keys historically declared in teamcity-plugin.xml — kept so
//      operators who set them by hand keep working), then
//   3. the compiled-in default.
//
// Values consumed per-operation (api version, cache TTL/grace) are pushed
// into the live beans by `applyTo`, called at startup and again whenever
// the admin saves settings — so edits take effect without a restart.
class BridgeServerSettings(
    private val storage: PluginSettingsStorage,
) {

    // ----- server tuning -----

    // Optional global override of the GitHub REST API base. Blank means
    // "derive per connection from the connection's GitHub URL"
    // (github.com -> api.github.com, GHE -> <host>/api/v3).
    fun apiBaseOverride(): String? = resolve(KEY_API_BASE, LEGACY_API_BASE, "")
        ?.takeIf { it.isNotBlank() && it != GitHubClient.DEFAULT_API_BASE }

    fun apiVersion(): String =
        resolve(KEY_API_VERSION, LEGACY_API_VERSION, GitHubClient.DEFAULT_API_VERSION)
            ?.takeIf { it.isNotBlank() } ?: GitHubClient.DEFAULT_API_VERSION

    fun prInfoCacheTtlSeconds(): Long =
        longSetting(KEY_TTL_SECONDS, LEGACY_TTL_SECONDS, PrInfoCache.DEFAULT_TTL_MS / 1000L)

    fun prInfoStaleGraceSeconds(): Long =
        longSetting(KEY_STALE_GRACE_SECONDS, null, PrInfoCache.DEFAULT_STALE_GRACE_MS / 1000L)

    // ----- HTTP resilience (consumed by the retry layer) -----

    fun httpMaxAttempts(): Int =
        longSetting(KEY_HTTP_MAX_ATTEMPTS, null, DEFAULT_HTTP_MAX_ATTEMPTS.toLong())
            .coerceIn(1L, 10L).toInt()

    fun httpBaseDelayMs(): Long =
        longSetting(KEY_HTTP_BASE_DELAY_MS, null, DEFAULT_HTTP_BASE_DELAY_MS).coerceIn(0L, 60_000L)

    // ----- feature flags -----

    fun replayProtectionEnabled(): Boolean = boolSetting(KEY_REPLAY_ENABLED, true)

    fun dryRun(): Boolean = boolSetting(KEY_DRY_RUN, false)

    fun metricsEnabled(): Boolean = boolSetting(KEY_METRICS_ENABLED, true)

    fun legacyAliasesEnabled(): Boolean = boolSetting(KEY_LEGACY_ALIASES, false)

    // Sticky PR summary comment. Default off: it needs the App's
    // pull-requests/issues write permission and posts to the PR thread.
    fun prCommentEnabled(): Boolean = boolSetting(KEY_PR_COMMENT_ENABLED, false)

    // Resolve the PR of a build launched on a plain branch ref (not a
    // `pull/N` ref) from its head commit, so branch builds get the PR
    // parameters, the draft/ready tag and the summary comment. On by
    // default: one extra `GET /commits/{sha}/pulls` per built commit,
    // cached (negative answers included). Turn off to keep branch builds
    // strictly PR-unaware.
    fun branchPrLookupEnabled(): Boolean = boolSetting(KEY_BRANCH_PR_LOOKUP, true)

    // "Re-run all checks" from GitHub (`check_suite.rerequested`): when on,
    // only build configurations whose last build at that commit FAILED are
    // re-run. Default off = re-run every opted-in build configuration, which
    // is what the GitHub button says it does.
    fun rerunAllOnlyFailed(): Boolean = boolSetting(KEY_RERUN_ONLY_FAILED, false)

    // List the build's artefacts (with a link to them) in the completed
    // Check Run and in the sticky PR comment. On by default: it is what makes
    // a PR a usable hand-off to a reviewer or a tester, and it costs one local
    // artifact listing per finished build.
    fun artifactLinksEnabled(): Boolean = boolSetting(KEY_ARTIFACT_LINKS, true)

    // Tag every PR build with its PR number, so a build stays findable by PR
    // long after it ran — that is what the Branches & PRs tab and TeamCity's
    // own tag filter key on. On by default; turn it off to keep the tag list
    // clean, at the cost of the PR column falling back to what the ref says.
    fun prTagEnabled(): Boolean = boolSetting(KEY_PR_TAG_ENABLED, true)

    // Prefix of that tag. Configurable because tags are shared with whatever
    // else a team puts there; blank or whitespace-only falls back to the
    // default rather than producing a bare number.
    fun prTagPrefix(): String =
        storage.get(KEY_PR_TAG_PREFIX)?.trim()?.takeIf { it.isNotEmpty() && !it.contains(' ') }
            ?: DEFAULT_PR_TAG_PREFIX

    // Bearer token for the external API. null = API disabled. Stored
    // separately (set/cleared from its own admin form) so a bulk settings
    // save never clears it.
    fun apiToken(): String? = storage.get(KEY_API_TOKEN)

    fun isApiEnabled(): Boolean = apiToken() != null

    // ----- plugin-managed GitHub App (created via the manifest flow) -----
    // Stored here so the plugin can mint tokens without a TeamCity OAuth
    // connection. A BuildType opts into it by setting connectionId to the
    // sentinel "managed".
    fun managedAppId(): String? = storage.get(KEY_APP_ID)
    fun managedAppPrivateKey(): String? = storage.get(KEY_APP_PRIVATE_KEY)
    fun managedAppSlug(): String? = storage.get(KEY_APP_SLUG)
    fun hasManagedApp(): Boolean = managedAppId() != null && managedAppPrivateKey() != null

    // Allowlist of `owner/repo` slugs the bridge will act on. Empty list
    // means "no restriction". Matching is case-insensitive.
    fun repoAllowlist(): List<String> =
        storage.get(KEY_REPO_ALLOWLIST)
            ?.split(',', '\n')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    fun isRepoAllowed(slug: String): Boolean {
        val allow = repoAllowlist()
        return allow.isEmpty() || allow.any { it.equals(slug, ignoreCase = true) }
    }

    // GitHub author_association values trusted to trigger builds via PR
    // comments. Default OWNER/MEMBER/COLLABORATOR (i.e. people with write
    // access), so arbitrary outside commenters cannot start builds.
    fun commentTriggerAllowedAssociations(): Set<String> =
        (storage.get(KEY_COMMENT_ASSOCIATIONS) ?: DEFAULT_COMMENT_ASSOCIATIONS)
            .split(',', '\n').map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()

    fun isCommentAuthorAllowed(association: String): Boolean {
        val allowed = commentTriggerAllowedAssociations()
        // An empty allowlist means the operator explicitly opened it to all.
        return allowed.isEmpty() || association.uppercase() in allowed
    }

    // Push per-operation values into the live beans. Idempotent; safe to
    // call repeatedly (startup + every admin save).
    fun applyTo(gitHubClient: GitHubClient, prInfoCache: PrInfoCache) {
        gitHubClient.apiVersion = apiVersion()
        gitHubClient.maxAttempts = httpMaxAttempts()
        gitHubClient.baseDelayMs = httpBaseDelayMs()
        prInfoCache.ttlMs = prInfoCacheTtlSeconds() * 1000L
        prInfoCache.staleGraceMs = prInfoStaleGraceSeconds() * 1000L
        LOG.info(
            "Applied GitHub Bridge server settings: apiVersion=${apiVersion()}, " +
                "prInfoTtl=${prInfoCacheTtlSeconds()}s, staleGrace=${prInfoStaleGraceSeconds()}s, " +
                "retry=${httpMaxAttempts()}x@${httpBaseDelayMs()}ms, replay=${replayProtectionEnabled()}, " +
                "dryRun=${dryRun()}, metrics=${metricsEnabled()}, legacyAliases=${legacyAliasesEnabled()}, " +
                "branchPrLookup=${branchPrLookupEnabled()}, " +
                "rerunAllOnlyFailed=${rerunAllOnlyFailed()}, artifactLinks=${artifactLinksEnabled()}, " +
                "prTag=${if (prTagEnabled()) prTagPrefix() + "<n>" else "off"}, " +
                "allowlist=${repoAllowlist().size} entr(y/ies)"
        )
    }

    private fun resolve(key: String, legacyProperty: String?, default: String): String? {
        storage.get(key)?.let { return it }
        if (legacyProperty != null) {
            val legacy = TeamCityProperties.getProperty(legacyProperty)
            if (legacy.isNotBlank()) return legacy
        }
        return default
    }

    private fun longSetting(key: String, legacyProperty: String?, default: Long): Long {
        val raw = resolve(key, legacyProperty, default.toString()) ?: return default
        return raw.trim().toLongOrNull() ?: default
    }

    private fun boolSetting(key: String, default: Boolean): Boolean =
        storage.get(key)?.let { it.equals("true", ignoreCase = true) } ?: default

    companion object {
        private val LOG = Logger.getInstance(BridgeServerSettings::class.java.name)

        // Plugin-file keys.
        const val KEY_API_BASE: String = "api.base"
        const val KEY_API_VERSION: String = "api.version"
        const val KEY_TTL_SECONDS: String = "prinfo.cache.ttl.seconds"
        const val KEY_STALE_GRACE_SECONDS: String = "prinfo.cache.staleGrace.seconds"
        const val KEY_HTTP_MAX_ATTEMPTS: String = "http.retry.maxAttempts"
        const val KEY_HTTP_BASE_DELAY_MS: String = "http.retry.baseDelayMs"
        const val KEY_REPLAY_ENABLED: String = "webhook.replay.enabled"
        const val KEY_DRY_RUN: String = "dryRun"
        const val KEY_METRICS_ENABLED: String = "metrics.enabled"
        const val KEY_LEGACY_ALIASES: String = "legacyAliases.enabled"
        const val KEY_PR_COMMENT_ENABLED: String = "prComment.enabled"
        const val KEY_BRANCH_PR_LOOKUP: String = "branchPrLookup.enabled"
        const val KEY_RERUN_ONLY_FAILED: String = "rerunAll.onlyFailed"
        const val KEY_ARTIFACT_LINKS: String = "checkRun.artifactLinks"
        const val KEY_PR_TAG_ENABLED: String = "prTag.enabled"
        const val KEY_PR_TAG_PREFIX: String = "prTag.prefix"
        const val DEFAULT_PR_TAG_PREFIX: String = "pr-"
        const val KEY_REPO_ALLOWLIST: String = "repo.allowlist"
        const val KEY_COMMENT_ASSOCIATIONS: String = "comment.allowedAssociations"
        const val DEFAULT_COMMENT_ASSOCIATIONS: String = "OWNER,MEMBER,COLLABORATOR"
        const val KEY_API_TOKEN: String = "api.token"
        const val KEY_APP_ID: String = "app.id"
        const val KEY_APP_PRIVATE_KEY: String = "app.privateKey"
        const val KEY_APP_SLUG: String = "app.slug"

        // Sentinel connectionId value that routes minting to the
        // plugin-managed App instead of a TeamCity OAuth connection.
        const val MANAGED_CONNECTION_ID: String = "managed"

        // Legacy internal-property fallbacks (declared in teamcity-plugin.xml).
        const val LEGACY_API_BASE: String = "teamcity.github.bridge.api.base"
        const val LEGACY_API_VERSION: String = "teamcity.github.bridge.api.version"
        const val LEGACY_TTL_SECONDS: String = "teamcity.github.bridge.prinfo.cache.ttl.seconds"

        const val DEFAULT_HTTP_MAX_ATTEMPTS: Int = 3
        const val DEFAULT_HTTP_BASE_DELAY_MS: Long = 500L
    }
}
