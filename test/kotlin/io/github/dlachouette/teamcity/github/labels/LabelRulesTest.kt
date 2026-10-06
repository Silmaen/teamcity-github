package io.github.dlachouette.teamcity.github.labels

import io.github.dlachouette.teamcity.github.labels.LabelRules.Team
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LabelRulesTest {

    private fun facts(
        author: String = "alice",
        base: String = "main",
        head: String = "Feature/x",
        title: String = "Add a thing",
        files: List<String> = listOf("src/net/socket.kt"),
        members: Map<Team, Set<String>> = emptyMap(),
        onFiles: () -> Unit = {},
    ) = LabelRules.Facts(author, base, head, title, { onFiles(); files }, { team, login -> members[team]?.contains(login) ?: false })

    private fun labels(rules: String, facts: LabelRules.Facts): List<String> {
        val parsed = LabelRules.parse(rules)
        assertTrue(parsed.errors.isEmpty(), "errors: ${parsed.errors}")
        return LabelRules.labelsFor(parsed.rules, facts)
    }

    @Test
    fun `each condition kind`() {
        val rules = """
            # comment
            network <= paths +:src/net/**, -:src/net/test/**
            mine <= author Alice, bob
            core <= author @acme/core
            release <= base +:Release/*
            feature <= head +:Feature/*
            docs <= title ^docs
        """.trimIndent()
        assertEquals(
            listOf("network", "mine", "core", "feature"),
            labels(rules, facts(members = mapOf(Team("acme", "core") to setOf("alice")))),
        )
        assertEquals(
            listOf("release", "docs"),
            labels(rules, facts(author = "zoe", base = "Release/2026-10", head = "fix", title = "Docs: typo", files = listOf("README.md"))),
        )
    }

    @Test
    fun `all conditions of a rule must hold, and a label colon is fine`() {
        val rules = "type: net-docs <= paths +:docs/** ; title net"
        assertEquals(listOf("type: net-docs"), labels(rules, facts(title = "Net docs", files = listOf("docs/a.md"))))
        assertTrue(labels(rules, facts(title = "Other", files = listOf("docs/a.md"))).isEmpty())
    }

    @Test
    fun `changed files are fetched only when a paths condition is reached`() {
        var fetched = 0
        labels("x <= author bob ; paths +:src/**", facts(onFiles = { fetched++ }))
        assertEquals(0, fetched)
        labels("x <= paths +:src/**\ny <= paths +:lib/**", facts(onFiles = { fetched++ }))
        assertEquals(1, fetched)
    }

    @Test
    fun `invalid lines are reported with their number and skipped`() {
        val parsed = LabelRules.parse("ok <= title a\nno arrow here\nx <= path +:a\ny <= author @noslash\nz <= title (\nw <= ;")
        assertEquals(listOf("ok"), parsed.rules.map { it.label })
        assertEquals(listOf(2, 3, 4, 5, 6), parsed.errors.map { it.substringAfter("line ").substringBefore(":").toInt() })
    }

    @Test
    fun `labels are only added once and never against a removal`() {
        assertEquals(
            listOf("new"),
            PrLabeler.toAdd(matched = listOf("new", "present", "removed"), current = listOf("Present"), appliedBefore = setOf("removed")),
        )
    }
}
