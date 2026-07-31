package io.github.dlachouette.teamcity.github.web

import io.github.dlachouette.teamcity.github.api.GitHubClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// G19: the bridge is attached to one repository, never to its forks, so the
// head repository must survive parsing — from the webhook payload and from
// the REST answer alike.
class WebhookPayloadForkTest {

    private fun prEvent(headRepoJson: String) = """
        {"action":"opened",
         "repository":{"full_name":"acme/widget"},
         "pull_request":{"number":7,"draft":false,
           "head":{"sha":"deadbeef","ref":"Feature/x"$headRepoJson},
           "base":{"ref":"master"}}}
    """.trimIndent()

    @Test
    fun `same-repository PR carries the base repo as head repo`() {
        val parsed = WebhookPayloadParser.parsePullRequestEvent(prEvent(""","repo":{"full_name":"acme/widget"}"""))!!
        assertEquals("acme/widget", parsed.headRepo)
    }

    @Test
    fun `fork PR carries the fork slug`() {
        val parsed = WebhookPayloadParser.parsePullRequestEvent(prEvent(""","repo":{"full_name":"outsider/widget"}"""))!!
        assertEquals("outsider/widget", parsed.headRepo)
    }

    @Test
    fun `a missing head repo parses as blank so the guard can fail open`() {
        val parsed = WebhookPayloadParser.parsePullRequestEvent(prEvent(""))!!
        assertEquals("", parsed.headRepo)
    }

    @Test
    fun `review approval carries the head repo too`() {
        val json = """
            {"action":"submitted",
             "review":{"state":"approved"},
             "repository":{"full_name":"acme/widget"},
             "pull_request":{"number":9,"draft":false,
               "head":{"sha":"cafe","ref":"Bugfix/y","repo":{"full_name":"outsider/widget"}}}}
        """.trimIndent()
        assertEquals("outsider/widget", WebhookPayloadParser.parseReviewApproved(json)!!.headRepo)
    }

    @Test
    fun `REST pull request parsing exposes the head repo`() {
        val json = """
            {"number":42,"title":"t","state":"open","draft":false,
             "user":{"login":"alice"},
             "head":{"ref":"Feature/x","sha":"abc","repo":{"full_name":"outsider/widget"}},
             "base":{"ref":"master"}}
        """.trimIndent()
        assertEquals("outsider/widget", GitHubClient.parsePrInfo(json)!!.headRepo)
    }

    @Test
    fun `REST parsing tolerates a null head repo`() {
        val json = """
            {"number":42,"title":"t","state":"open","draft":false,
             "user":{"login":"alice"},
             "head":{"ref":"Feature/x","sha":"abc","repo":null},
             "base":{"ref":"master"}}
        """.trimIndent()
        assertEquals("", GitHubClient.parsePrInfo(json)!!.headRepo)
    }
}
