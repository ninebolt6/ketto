package net.ninebolt.onevsone.domain

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

/** Verifies the index <-> number mapping boundaries of SpawnSlot. */
class SpawnSlotTest {

    @Test
    fun `ofIndex and ofNumber accept only the two slots`() {
        assertEquals(SpawnSlot.FIRST, SpawnSlot.ofIndex(0))
        assertEquals(SpawnSlot.SECOND, SpawnSlot.ofIndex(1))
        assertNull(SpawnSlot.ofIndex(-1))
        assertNull(SpawnSlot.ofIndex(2))

        assertEquals(SpawnSlot.FIRST, SpawnSlot.ofNumber(1))
        assertEquals(SpawnSlot.SECOND, SpawnSlot.ofNumber(2))
        assertNull(SpawnSlot.ofNumber(0))
        assertNull(SpawnSlot.ofNumber(3))
    }

    @Test
    fun `number is the user-facing 1-based counterpart of index`() {
        SpawnSlot.entries.forEach { slot ->
            assertEquals(slot.index + 1, slot.number)
            assertEquals(slot, SpawnSlot.ofIndex(slot.index))
            assertEquals(slot, SpawnSlot.ofNumber(slot.number))
        }
    }
}
