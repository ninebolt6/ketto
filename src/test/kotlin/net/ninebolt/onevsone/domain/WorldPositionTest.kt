package net.ninebolt.onevsone.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorldPositionTest {

    @Test
    fun `new rejects a blank world name`() {
        assertFailsWith<IllegalArgumentException> {
            WorldPosition.new(" ", 0.0, 64.0, 0.0)
        }
    }

    @Test
    fun `new rejects non-finite coordinates`() {
        assertFailsWith<IllegalArgumentException> {
            WorldPosition.new("world", Double.NaN, 64.0, 0.0)
        }
        assertFailsWith<IllegalArgumentException> {
            WorldPosition.new("world", 0.0, Double.POSITIVE_INFINITY, 0.0)
        }
        assertFailsWith<IllegalArgumentException> {
            WorldPosition.new("world", 0.0, 64.0, Double.NEGATIVE_INFINITY)
        }
    }

    @Test
    fun `new rejects non-finite yaw or pitch`() {
        assertFailsWith<IllegalArgumentException> {
            WorldPosition.new("world", 0.0, 64.0, 0.0, yaw = Float.NaN)
        }
        assertFailsWith<IllegalArgumentException> {
            WorldPosition.new("world", 0.0, 64.0, 0.0, pitch = Float.POSITIVE_INFINITY)
        }
    }

    @Test
    fun `new accepts explicit yaw and pitch`() {
        val pos = WorldPosition.new("world", 1.0, 64.0, 3.0, yaw = 45f, pitch = -30f)
        assertEquals(45f, pos.yaw)
        assertEquals(-30f, pos.pitch)
    }
}
