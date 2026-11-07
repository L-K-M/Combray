package ch.lkmc.combray.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class EngineInputConnectionTest {
    @Test
    fun anEditIsCappedSoAHostileImeCannotHangTheEngine() {
        assertEquals(0, EngineInputConnection.boundedEditLength(-1))
        assertEquals(1, EngineInputConnection.boundedEditLength(1))
        assertEquals(EngineInputConnection.MAX_EDIT_LENGTH, EngineInputConnection.boundedEditLength(Int.MAX_VALUE))
    }
}
