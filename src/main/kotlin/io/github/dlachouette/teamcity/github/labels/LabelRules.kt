package io.github.dlachouette.teamcity.github.labels

import io.github.dlachouette.teamcity.github.feature.BranchSpecMatcher

// Rules that label a pull request: `teamcity.github.bridge.labelRules`, one rule
// per line.
//
//     network   <= paths +:src/net/**, -:src/net/test/**
//     team-core <= author @acme/core, alice
//     docs      <= paths +:docs/** ; title ^docs
//     release   <= base +:Release/*
//
// `<label> <= <condition> ; <condition> …` — every condition of a rule must hold
// (AND); a label is added when any of its rules holds. Conditions:
//
//   - `paths`  — VCS-filter entries, comma-separated (same syntax as
//                `pathFilter`): holds when a changed file matches;
//   - `author` — logins and `@org/team` slugs, comma-separated: holds when the
//                author is one of them (team membership needs the App's
//                organisation `members: read`);
//   - `base` / `head` — branch-filter entries, comma-separated, matched on the
//                base or head branch;
//   - `title`  — a regular expression, searched case-insensitively.
//
// `#` starts a comment line. A label is the text left of the first ` <= `, so it
// may hold anything else (`type: bug`). A condition value cannot hold `;`.
object LabelRules {

    data class Rule(val label: String, val conditions: List<Condition>, val line: Int)

    sealed interface Condition {
        data class Paths(val spec: BranchSpecMatcher) : Condition
        data class Author(val logins: Set<String>, val teams: List<Team>) : Condition
        data class Base(val spec: BranchSpecMatcher) : Condition
        data class Head(val spec: BranchSpecMatcher) : Condition
        data class Title(val regex: Regex) : Condition
    }

    data class Team(val org: String, val slug: String)

    data class Parsed(val rules: List<Rule>, val errors: List<String>)

    // What a rule is checked against. `changedFiles` is fetched only when a
    // `paths` condition is reached; `isTeamMember` answers null when GitHub
    // cannot tell (missing permission), which counts as "not a member".
    data class Facts(
        val author: String,
        val baseRef: String,
        val headRef: String,
        val title: String,
        val changedFiles: () -> List<String>,
        val isTeamMember: (Team, String) -> Boolean?,
    )

    private const val ARROW = " <= "
    private val KEYS = setOf("paths", "author", "base", "head", "title")

    fun parse(text: String?): Parsed {
        val rules = mutableListOf<Rule>()
        val errors = mutableListOf<String>()
        text.orEmpty().lines().forEachIndexed { index, raw ->
            val lineNo = index + 1
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val arrow = line.indexOf(ARROW)
            if (arrow <= 0) {
                errors += "line $lineNo: expected `<label> <= <condition>`"
                return@forEachIndexed
            }
            val label = line.substring(0, arrow).trim()
            val conditions = mutableListOf<Condition>()
            var ok = true
            line.substring(arrow + ARROW.length).split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { part ->
                val key = part.substringBefore(' ').lowercase()
                val value = part.substringAfter(' ', "").trim()
                when {
                    key !in KEYS -> { errors += "line $lineNo: unknown condition `$key` (use ${KEYS.joinToString(", ")})"; ok = false }
                    value.isEmpty() -> { errors += "line $lineNo: `$key` needs a value"; ok = false }
                    else -> condition(key, value)?.let { conditions += it }
                        ?: run { errors += "line $lineNo: invalid `$key` value `$value`"; ok = false }
                }
            }
            if (ok && conditions.isEmpty()) {
                errors += "line $lineNo: a rule needs at least one condition"
                ok = false
            }
            if (ok) rules += Rule(label, conditions, lineNo)
        }
        return Parsed(rules, errors)
    }

    private fun condition(key: String, value: String): Condition? {
        val entries = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return when (key) {
            "paths" -> spec(entries)?.let { Condition.Paths(it) }
            "base" -> spec(entries)?.let { Condition.Base(it) }
            "head" -> spec(entries)?.let { Condition.Head(it) }
            "author" -> {
                val teams = entries.filter { it.startsWith("@") }.map { it.removePrefix("@") }
                if (teams.any { it.count { c -> c == '/' } != 1 || it.startsWith("/") || it.endsWith("/") }) return null
                Condition.Author(
                    entries.filterNot { it.startsWith("@") }.map { it.lowercase() }.toSet(),
                    teams.map { Team(it.substringBefore('/'), it.substringAfter('/')) },
                )
            }
            "title" -> runCatching { Regex(value, RegexOption.IGNORE_CASE) }.getOrNull()?.let { Condition.Title(it) }
            else -> null
        }
    }

    private fun spec(entries: List<String>): BranchSpecMatcher? {
        val text = entries.joinToString("\n")
        if (BranchSpecMatcher.validate(text) != null) return null
        return BranchSpecMatcher.parse(text).takeIf { !it.isEmpty() }
    }

    // The labels whose rules hold, in rule order, each once.
    fun labelsFor(rules: List<Rule>, facts: Facts): List<String> {
        val files by lazy(facts.changedFiles)
        return rules.filter { rule -> rule.conditions.all { holds(it, facts) { files } } }
            .map { it.label }
            .distinct()
    }

    private fun holds(c: Condition, facts: Facts, files: () -> List<String>): Boolean = when (c) {
        is Condition.Paths -> files().any { c.spec.matches(it) }
        is Condition.Base -> c.spec.matches(facts.baseRef)
        is Condition.Head -> c.spec.matches(facts.headRef)
        is Condition.Title -> c.regex.containsMatchIn(facts.title)
        is Condition.Author -> facts.author.lowercase() in c.logins ||
            c.teams.any { facts.isTeamMember(it, facts.author) == true }
    }
}
