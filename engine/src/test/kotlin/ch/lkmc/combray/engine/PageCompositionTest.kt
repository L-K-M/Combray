package ch.lkmc.combray.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PageCompositionTest {
    private val seen = mutableListOf<String>()
    private var nextId = 0L
    private val composition = PageComposition(
        send = { id, phase, text -> seen += "$id:$phase:$text" },
        commit = { seen += "commit:$it" },
        nextId = { ++nextId },
    )

    @Test
    fun eachKeystrokeReplacesThePreviewAndCommitReplacesItOnce() {
        composition.update("n")
        composition.update("ni")
        composition.commit("你")
        composition.finish()
        composition.close()
        assertEquals(listOf("1:START:", "1:UPDATE:n", "1:UPDATE:ni", "1:END:你"), seen)
    }

    @Test
    fun emptyUpdateAndEmptyCommitAreNotDropped() {
        composition.update("été")
        composition.update("")
        composition.commit("")
        assertEquals(listOf("1:START:", "1:UPDATE:été", "1:UPDATE:", "1:END:"), seen)
    }

    @Test
    fun closingFinishesOnceAndRetiresTheConnection() {
        composition.update("é🦊")
        composition.close()
        composition.close()
        assertFalse(composition.update("late"))
        assertFalse(composition.commit("late"))
        assertFalse(composition.finish())
        assertEquals(listOf("1:START:", "1:UPDATE:é🦊", "1:END:é🦊"), seen)
    }

    @Test
    fun finishKeepsTheTextWithoutCommittingItAgainAndASecondWordGetsANewIdentity() {
        composition.commit("plain")
        composition.update("one")
        composition.finish()
        composition.update("two")
        composition.finish()
        assertEquals(listOf("commit:plain", "1:START:", "1:UPDATE:one", "1:END:one", "2:START:", "2:UPDATE:two", "2:END:two"), seen)
    }
}
