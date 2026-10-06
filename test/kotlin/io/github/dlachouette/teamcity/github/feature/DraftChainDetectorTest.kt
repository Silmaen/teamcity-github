package io.github.dlachouette.teamcity.github.feature

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// A composite that runs on drafts builds its whole chain on every draft push,
// overriding the dependencies set to skip drafts. The self-test warns.
class DraftChainDetectorTest {

    // `drafts` is the effective triggerOnPrDraft; null = not opted in.
    private class Bt(
        val id: String,
        val composite: Boolean = false,
        val drafts: Boolean? = false,
        val deps: MutableList<Bt> = mutableListOf(),
    )

    private fun find(vararg bts: Bt) = DraftChainDetector.find(
        bts.toList(),
        id = { it.id },
        isComposite = { it.composite },
        runsOnDrafts = { it.drafts },
        dependencies = { it.deps },
    )

    @Test
    fun `a draft-enabled composite over draft-skipping leaves is reported`() {
        val fast = Bt("Fast", drafts = true)
        val full = Bt("Full", drafts = false)
        val gate = Bt("Gate", composite = true, drafts = true, deps = mutableListOf(full, fast))
        assertEquals(
            listOf(DraftChainDetector.Mismatch("Gate", listOf("Full"))),
            find(gate, fast, full),
        )
    }

    @Test
    fun `nothing to report when the composite skips drafts or is not composite`() {
        val leaf = Bt("Leaf", drafts = false)
        assertTrue(find(Bt("Gate", composite = true, drafts = false, deps = mutableListOf(leaf)), leaf).isEmpty())
        assertTrue(find(Bt("Chain", composite = false, drafts = true, deps = mutableListOf(leaf)), leaf).isEmpty())
        assertTrue(find(Bt("Gate", composite = true, drafts = null, deps = mutableListOf(leaf)), leaf).isEmpty())
    }

    @Test
    fun `a dependency the bridge does not gate is not a mismatch`() {
        val plain = Bt("Plain", drafts = null)
        assertTrue(find(Bt("Gate", composite = true, drafts = true, deps = mutableListOf(plain)), plain).isEmpty())
    }

    @Test
    fun `the chain is walked transitively and survives a diamond`() {
        val deep = Bt("Deep", drafts = false)
        val a = Bt("A", drafts = true, deps = mutableListOf(deep))
        val b = Bt("B", drafts = true, deps = mutableListOf(deep))
        val gate = Bt("Gate", composite = true, drafts = true, deps = mutableListOf(a, b))
        assertEquals(listOf(DraftChainDetector.Mismatch("Gate", listOf("Deep"))), find(gate, a, b, deep))
    }
}
