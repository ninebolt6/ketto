package net.ninebolt.onevsone.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpawnSlotTest {

    @Test
    fun `ofIndex accepts only the two slots`() {
        assertEquals(SpawnSlot.FIRST, SpawnSlot.ofIndex(0))
        assertEquals(SpawnSlot.SECOND, SpawnSlot.ofIndex(1))
        assertNull(SpawnSlot.ofIndex(-1))
        assertNull(SpawnSlot.ofIndex(2))
    }

    @Test
    fun `number is the user-facing 1-based counterpart of index`() {
        SpawnSlot.entries.forEach { slot ->
            assertEquals(slot.index + 1, slot.number)
            assertEquals(slot, SpawnSlot.ofIndex(slot.index))
        }
    }
}
