package io.github.dlachouette.teamcity.github.api

import io.github.dlachouette.teamcity.github.testsupport.LoggerBootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Required check names come from two places GitHub keeps them: classic branch
// protection and rulesets. Both shapes are parsed.
class GitHubClientProtectionTest {

    init { LoggerBootstrap.install() }

    @Test
    fun `classic protection lists names in contexts and in checks`() {
        val json = """{"strict":true,"contexts":["TeamCity / Build","legacy"],"checks":[{"context":"TeamCity / Build","app_id":1},{"context":"PR Ready","app_id":null}]}"""
        assertEquals(setOf("TeamCity / Build", "legacy", "PR Ready"), GitHubClient.parseClassicRequiredChecks(json))
    }

    @Test
    fun `rulesets name checks only in required_status_checks rules`() {
        val json = """[
            {"type":"pull_request","parameters":{"required_approving_review_count":1}},
            {"type":"required_status_checks","ruleset_id":7,"parameters":{"strict_required_status_checks_policy":false,
              "required_status_checks":[{"context":"PR Ready","integration_id":42},{"context":"Lint"}]}},
            {"type":"deletion"}
        ]"""
        assertEquals(setOf("PR Ready", "Lint"), GitHubClient.parseRulesetRequiredChecks(json))
    }

    @Test
    fun `garbage parses as nothing required`() {
        assertTrue(GitHubClient.parseClassicRequiredChecks("not json").isEmpty())
        assertTrue(GitHubClient.parseRulesetRequiredChecks("{}").isEmpty())
        assertTrue(GitHubClient.parseBranchNames("{}").isEmpty())
        assertNull(GitHubClient.parseDefaultBranch("{}"))
    }

    @Test
    fun `branch names and the default branch`() {
        assertEquals(listOf("main", "Release/2026-06"), GitHubClient.parseBranchNames("""[{"name":"main"},{"name":"Release/2026-06"},{"x":1}]"""))
        assertEquals("main", GitHubClient.parseDefaultBranch("""{"full_name":"acme/widget","default_branch":"main"}"""))
    }

    @Test
    fun `a branch with a slash is one path segment`() {
        assertEquals("Release%2F2026-06", GitHubClient.encodePathSegment("Release/2026-06"))
        assertEquals("a%20b", GitHubClient.encodePathSegment("a b"))
    }
}
