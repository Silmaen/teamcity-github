package io.github.dlachouette.teamcity.github.feature

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BridgeTriggerTest {

    @Test
    fun `only AUTO is non-explicit`() {
        assertFalse(BridgeTrigger.AUTO.isExplicit)
        assertTrue(BridgeTrigger.COMMAND.isExplicit)
        assertTrue(BridgeTrigger.MANUAL.isExplicit)
    }

    @Test
    fun `a user trigger is MANUAL whatever the marker says`() {
        assertEquals(
            BridgeTrigger.MANUAL,
            BridgeTriggerMarker.of(BridgeTriggerMarker.parametersFor(BridgeTrigger.COMMAND), triggeredByUser = true),
        )
        assertEquals(BridgeTrigger.MANUAL, BridgeTriggerMarker.of(emptyMap(), triggeredByUser = true))
    }

    @Test
    fun `the marker promotes an unattributed build to COMMAND`() {
        assertEquals(
            BridgeTrigger.COMMAND,
            BridgeTriggerMarker.of(BridgeTriggerMarker.parametersFor(BridgeTrigger.COMMAND), triggeredByUser = false),
        )
    }

    @Test
    fun `marker matching is case-insensitive`() {
        assertEquals(
            BridgeTrigger.COMMAND,
            BridgeTriggerMarker.of(mapOf(BridgeTriggerMarker.PARAM to "CoMmAnD"), triggeredByUser = false),
        )
    }

    @Test
    fun `no marker and no user means AUTO`() {
        assertEquals(BridgeTrigger.AUTO, BridgeTriggerMarker.of(emptyMap(), triggeredByUser = false))
        assertEquals(
            BridgeTrigger.AUTO,
            BridgeTriggerMarker.of(mapOf(BridgeTriggerMarker.PARAM to "auto"), triggeredByUser = false),
        )
        // An unrelated value must not be mistaken for a command.
        assertEquals(
            BridgeTrigger.AUTO,
            BridgeTriggerMarker.of(mapOf(BridgeTriggerMarker.PARAM to "something-else"), triggeredByUser = false),
        )
    }

    @Test
    fun `parametersFor stamps the lowercase trigger name`() {
        assertEquals(
            mapOf(BridgeTriggerMarker.PARAM to "command"),
            BridgeTriggerMarker.parametersFor(BridgeTrigger.COMMAND),
        )
    }
}

class BridgeRefsTest {

    @Test
    fun `prRef builds the pull ref`() {
        assertEquals("pull/189", BridgeRefs.prRef(189))
    }

    @Test
    fun `prNumberFromRef reads back a pull ref`() {
        assertEquals(189, BridgeRefs.prNumberFromRef("pull/189"))
    }

    @Test
    fun `prNumberFromRef rejects everything else`() {
        listOf(null, "", "main", "Feature/pull/1", "pull/", "pull/x", "pull/-3", "pull/0", "PULL/1").forEach {
            assertEquals(null, BridgeRefs.prNumberFromRef(it), "ref=$it")
        }
    }
}

class PrBuildRefParseTest {

    @Test
    fun `branch is opt-in and case-insensitive`() {
        assertEquals(PrBuildRef.BRANCH, PrBuildRef.parse("branch"))
        assertEquals(PrBuildRef.BRANCH, PrBuildRef.parse("BRANCH"))
        assertEquals(PrBuildRef.BRANCH, PrBuildRef.parse(" branch "))
    }

    @Test
    fun `anything else keeps the historical pull ref`() {
        listOf(null, "", "pull", "PULL", "head", "nonsense").forEach {
            assertEquals(PrBuildRef.PULL, PrBuildRef.parse(it), "raw=$it")
        }
    }
}
