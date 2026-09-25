package net.ninebolt.onevsone.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlayerStatsTest {

    @Test
    fun `new rejects negative counts`() {
        assertFailsWith<IllegalArgumentException> { PlayerStats.new(-1, 0) }
        assertFailsWith<IllegalArgumentException> { PlayerStats.new(0, -1) }
    }

    @Test
    fun `ratio uses one as the denominator when there are no losses`() {
        assertEquals(3.0, PlayerStats.new(3, 0).ratio)
        assertEquals(1.5, PlayerStats.new(3, 2).ratio)
    }
}
