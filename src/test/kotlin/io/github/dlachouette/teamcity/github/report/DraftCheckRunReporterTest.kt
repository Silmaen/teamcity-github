package io.github.dlachouette.teamcity.github.report

import io.github.dlachouette.teamcity.github.filter.DraftAwareBuildFilter
import io.github.dlachouette.teamcity.github.testsupport.LoggerBootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DraftCheckRunReporterTest {

    init { LoggerBootstrap.install() }

    private val optInParams = mapOf(
        DraftAwareBuildFilter.PARAM_IGNORE_DRAFTS to "true",
        DraftAwareBuildFilter.PARAM_REPO_SLUG to "acme/widget",
        DraftAwareBuildFilter.PARAM_CONNECTION_ID to "CID_abc",
    )

    @Test
    fun `emits a request for a draft PR on an opt-in build type`() {
        val req = DraftCheckRunReporter.buildRequest(
            branchName = "pull/42",
            params = optInParams,
            isDraft = true,
            headSha = "abc123",
            buildTypeFullName = "Build / Linux x64 / Clang",
            prNumber = 42,
        )
        assertNotNull(req)
        assertEquals("TeamCity / Build / Linux x64 / Clang", req!!.name)
        assertEquals("abc123", req.headSha)
    }

    @Test
    fun `returns null for non-PR branches`() {
        val req = DraftCheckRunReporter.buildRequest(
            branchName = "main",
            params = optInParams,
            isDraft = true,
            headSha = "abc",
            buildTypeFullName = "X",
            prNumber = 0,
        )
        assertNull(req)
    }

    @Test
    fun `returns null when the build type opted out of draft suppression`() {
        val optOut = optInParams.toMutableMap().apply {
            this[DraftAwareBuildFilter.PARAM_IGNORE_DRAFTS] = "false"
        }
        val req = DraftCheckRunReporter.buildRequest(
            branchName = "pull/42",
            params = optOut,
            isDraft = true,
            headSha = "abc",
            buildTypeFullName = "X",
            prNumber = 42,
        )
        assertNull(req)
    }

    @Test
    fun `returns null when the PR is not draft`() {
        val req = DraftCheckRunReporter.buildRequest(
            branchName = "pull/42",
            params = optInParams,
            isDraft = false,
            headSha = "abc",
            buildTypeFullName = "X",
            prNumber = 42,
        )
        assertNull(req)
    }

    @Test
    fun `returns null when one of the bridge params is missing`() {
        val incomplete = optInParams.toMutableMap().apply { remove(DraftAwareBuildFilter.PARAM_REPO_SLUG) }
        val req = DraftCheckRunReporter.buildRequest(
            branchName = "pull/42",
            params = incomplete,
            isDraft = true,
            headSha = "abc",
            buildTypeFullName = "X",
            prNumber = 42,
        )
        assertNull(req)
    }

    @Test
    fun `returns null when head sha is blank`() {
        val req = DraftCheckRunReporter.buildRequest(
            branchName = "pull/42",
            params = optInParams,
            isDraft = true,
            headSha = "",
            buildTypeFullName = "X",
            prNumber = 42,
        )
        assertNull(req)
    }

    @Test
    fun `propagates the build detailsUrl when provided`() {
        val req = DraftCheckRunReporter.buildRequest(
            branchName = "pull/42",
            params = optInParams,
            isDraft = true,
            headSha = "abc123",
            buildTypeFullName = "Build / X",
            prNumber = 42,
            detailsUrl = "https://tc.example.com/buildQueue/CI_Build/queued?buildId=123",
        )
        assertNotNull(req)
        assertEquals(
            "https://tc.example.com/buildQueue/CI_Build/queued?buildId=123",
            req!!.detailsUrl,
        )
    }
}
