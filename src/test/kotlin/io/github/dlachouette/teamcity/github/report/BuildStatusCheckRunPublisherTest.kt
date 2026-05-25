package io.github.dlachouette.teamcity.github.report

import io.github.dlachouette.teamcity.github.api.CheckRunConclusion
import io.github.dlachouette.teamcity.github.testsupport.LoggerBootstrap
import jetbrains.buildServer.messages.Status
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BuildStatusCheckRunPublisherTest {

    init { LoggerBootstrap.install() }

    @Test
    fun `maps normal status to success`() {
        val m = BuildStatusCheckRunPublisher.mapBuildOutcome(Status.NORMAL, isInterrupted = false)
        assertEquals(CheckRunConclusion.SUCCESS, m.conclusion)
        assertEquals("Build passed", m.title)
    }

    @Test
    fun `maps warning to success (warnings do not fail builds in TC)`() {
        val m = BuildStatusCheckRunPublisher.mapBuildOutcome(Status.WARNING, isInterrupted = false)
        assertEquals(CheckRunConclusion.SUCCESS, m.conclusion)
    }

    @Test
    fun `maps failure to failure`() {
        val m = BuildStatusCheckRunPublisher.mapBuildOutcome(Status.FAILURE, isInterrupted = false)
        assertEquals(CheckRunConclusion.FAILURE, m.conclusion)
        assertEquals("Build failed", m.title)
    }

    @Test
    fun `maps error to failure`() {
        val m = BuildStatusCheckRunPublisher.mapBuildOutcome(Status.ERROR, isInterrupted = false)
        assertEquals(CheckRunConclusion.FAILURE, m.conclusion)
    }

    @Test
    fun `interrupted overrides any status to cancelled`() {
        val m1 = BuildStatusCheckRunPublisher.mapBuildOutcome(Status.NORMAL, isInterrupted = true)
        val m2 = BuildStatusCheckRunPublisher.mapBuildOutcome(Status.FAILURE, isInterrupted = true)
        assertEquals(CheckRunConclusion.CANCELLED, m1.conclusion)
        assertEquals(CheckRunConclusion.CANCELLED, m2.conclusion)
        assertEquals("Build cancelled", m1.title)
    }

    @Test
    fun `unknown status maps to neutral`() {
        val m = BuildStatusCheckRunPublisher.mapBuildOutcome(Status.UNKNOWN, isInterrupted = false)
        assertEquals(CheckRunConclusion.NEUTRAL, m.conclusion)
    }

    @Test
    fun `truncates summary above the limit`() {
        val long = "a".repeat(BuildStatusCheckRunPublisher.SUMMARY_MAX + 100)
        val truncated = BuildStatusCheckRunPublisher.truncateSummary(long)
        assertTrue(truncated.length <= BuildStatusCheckRunPublisher.SUMMARY_MAX + 20)
        assertTrue(truncated.endsWith("(truncated)"))
    }

    @Test
    fun `keeps short summary intact`() {
        val short = "Build OK, no warnings"
        assertEquals(short, BuildStatusCheckRunPublisher.truncateSummary(short))
    }
}
