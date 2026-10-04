package net.ninebolt.ketto.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class SpawnSlotTest {

    @Test
    fun `number is the user-facing 1-based counterpart of index`() {
        SpawnSlot.entries.forEach { slot ->
            assertEquals(slot.index + 1, slot.number)
        }
    }
}
