package net.ninebolt.onevsone.domain

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test

class BlockPositionTest {

    @Test
    fun `new holds integer block coordinates`() {
        val pos = BlockPosition.new("world", 3, 64, -2)
        assertEquals("world", pos.world)
        assertEquals(3, pos.x)
        assertEquals(64, pos.y)
        assertEquals(-2, pos.z)
    }

    @Test
    fun `new rejects blank world`() {
        assertFailsWith<IllegalArgumentException> {
            BlockPosition.new("", 3, 64, -2)
        }
    }
}
