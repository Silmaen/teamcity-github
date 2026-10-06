package io.github.dlachouette.teamcity.github.labels

import com.intellij.openapi.diagnostic.Logger
import io.github.dlachouette.teamcity.github.api.GitHubClient
import io.github.dlachouette.teamcity.github.api.TokenResolver
import io.github.dlachouette.teamcity.github.config.BridgeServerSettings
import io.github.dlachouette.teamcity.github.feature.BridgeFeatureConfig
import io.github.dlachouette.teamcity.github.feature.BridgeProjectParams
import io.github.dlachouette.teamcity.github.web.PrAction
import io.github.dlachouette.teamcity.github.web.PrEventPayload
import jetbrains.buildServer.serverSide.SBuildType
import jetbrains.buildServer.serverSide.ServerPaths
import java.io.File

// Applies `teamcity.github.bridge.labelRules` to a pull request.
//
// Runs on the events that can change what a rule sees — opened, reopened, a
// push (paths), an edit (title, base) — and never on `labeled` / `unlabeled`:
// the bridge's own labels must not feed the labelling back. They do reach the
// listener's ordinary `labeled` handling, so a rule adding a label a
// `labelFilter` gates on starts those builds: useful, and the operator's call.
//
// Labels are only ever added. One the bridge added and no longer finds on the
// pull request was removed by somebody, and stays removed (`AppliedLabelsStore`).
// Best effort throughout: a failure is logged and never stops the triggering.
class PrLabeler(
    private val tokenResolver: TokenResolver,
    private val gitHubClient: GitHubClient,
    private val serverSettings: BridgeServerSettings,
    serverPaths: ServerPaths,
) {

    private val store = AppliedLabelsStore(
        File(File(serverPaths.pluginDataDirectory, "teamcity-github-bridge"), AppliedLabelsStore.FILE_NAME),
    )

    fun onPullRequest(payload: PrEventPayload, candidates: List<Pair<SBuildType, BridgeFeatureConfig>>) {
        if (payload.action == PrAction.CLOSED) {
            store.forget(payload.repo.slug, payload.prNumber)
            return
        }
        if (payload.action !in TRIGGERING) return
        // Every project using this repository may have its own rules: all count.
        val withRules = candidates.mapNotNull { (bt, config) ->
            bt.project.parameters[BridgeProjectParams.LABEL_RULES]?.takeIf { it.isNotBlank() }?.let { Triple(bt, config, it) }
        }
        if (withRules.isEmpty()) return
        val rules = withRules.map { it.third }.distinct().flatMap { LabelRules.parse(it).rules }
        if (rules.isEmpty()) return
        val (bt, config, _) = withRules.first()
        try {
            apply(payload, rules, bt, config)
        } catch (e: Exception) {
            LOG.warn("Labelling ${payload.repo.slug}#${payload.prNumber} failed: ${e.message}")
        }
    }

    private fun apply(payload: PrEventPayload, rules: List<LabelRules.Rule>, bt: SBuildType, config: BridgeFeatureConfig) {
        val access = tokenResolver.resolveAccessToken(bt.project, config.connectionId, payload.repo) ?: return
        val facts = LabelRules.Facts(
            author = payload.author,
            baseRef = payload.baseRef,
            headRef = payload.headRef,
            title = payload.title,
            changedFiles = { gitHubClient.listPrFiles(access.token, payload.repo, payload.prNumber, access.apiBase) },
            isTeamMember = { team, login -> gitHubClient.isTeamMember(access.token, team.org, team.slug, login, access.apiBase) },
        )
        val toAdd = toAdd(
            matched = LabelRules.labelsFor(rules, facts),
            current = payload.labels,
            appliedBefore = store.appliedTo(payload.repo.slug, payload.prNumber),
        )
        if (toAdd.isEmpty()) return
        if (serverSettings.dryRun()) {
            LOG.info("[dry-run] would label ${payload.repo.slug}#${payload.prNumber} with $toAdd")
            return
        }
        when (val code = gitHubClient.addLabels(access.token, payload.repo, payload.prNumber, toAdd, access.apiBase)) {
            in 200..299 -> {
                store.record(payload.repo.slug, payload.prNumber, toAdd)
                LOG.info("Labelled ${payload.repo.slug}#${payload.prNumber} with $toAdd")
            }
            403 -> LOG.warn("Could not label ${payload.repo.slug}#${payload.prNumber}: the GitHub App lacks 'Issues: write'")
            else -> LOG.warn("Could not label ${payload.repo.slug}#${payload.prNumber} with $toAdd (HTTP $code)")
        }
    }

    companion object {
        private val LOG = Logger.getInstance(PrLabeler::class.java.name)

        private val TRIGGERING = setOf(PrAction.OPENED, PrAction.REOPENED, PrAction.SYNCHRONIZE, PrAction.EDITED, PrAction.READY_FOR_REVIEW)

        // Pure: what to add — matched, not there yet, and never added before
        // (added before and absent now means a human removed it).
        fun toAdd(matched: List<String>, current: Collection<String>, appliedBefore: Set<String>): List<String> {
            val present = current.map { it.lowercase() }.toSet()
            val before = appliedBefore.map { it.lowercase() }.toSet()
            return matched.filter { it.lowercase() !in present && it.lowercase() !in before }
        }
    }
}
