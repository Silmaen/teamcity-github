package io.github.dlachouette.teamcity.github.report

import io.github.dlachouette.teamcity.github.testsupport.LoggerBootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

// The open rows must survive a restart: that is when they are needed.
class OpenCheckRunRegistryTest {

    init { LoggerBootstrap.install() }

    @Test
    fun `open rows survive a reload, closed ones do not`(@TempDir tmp: Path) {
        val file = File(tmp.toFile(), "sub/open.tsv")
        var clock = 1_000L
        val reg = OpenCheckRunRegistry(file) { clock }
        reg.open(1, "Bt_A", "abc")
        clock = 2_000L
        reg.open(1, "Bt_A", "abc") // in_progress after queued: same row, first time kept
        reg.open(2, "Bt_B", "def")
        reg.close(2)
        reg.flush()

        val again = OpenCheckRunRegistry(file).also { it.load() }
        assertEquals(listOf(OpenCheckRunRegistry.Entry(1, "Bt_A", "abc", 1_000L)), again.snapshot())
    }

    @Test
    fun `a missing or garbled file loads as empty`(@TempDir tmp: Path) {
        assertTrue(OpenCheckRunRegistry(File(tmp.toFile(), "none.tsv")).also { it.load() }.snapshot().isEmpty())
        val bad = File(tmp.toFile(), "bad.tsv").apply { writeText("x\ty\n1\tBt\tsha\tnot-a-time\n7\tBt\tsha\t5\n") }
        assertEquals(listOf(7L), OpenCheckRunRegistry(bad).also { it.load() }.snapshot().map { it.promotionId })
    }
}
