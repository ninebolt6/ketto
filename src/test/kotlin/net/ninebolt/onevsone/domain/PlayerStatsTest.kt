package net.ninebolt.onevsone.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

class PlayerStatsTest {

    private val playerId = Uuid.random()

    @Test
    fun `restored rejects negative counts`() {
        assertFailsWith<IllegalArgumentException> { PlayerStats.restored(playerId, -1, 0) }
        assertFailsWith<IllegalArgumentException> { PlayerStats.restored(playerId, 0, -1) }
    }

    @Test
    fun `new starts with zero counts`() {
        val stats = PlayerStats.new(playerId)
        assertEquals(playerId, stats.playerId)
        assertEquals(0, stats.wins)
        assertEquals(0, stats.losses)
    }

    @Test
    fun `record transitions keep the other count`() {
        val stats = PlayerStats.restored(playerId, 3, 2)
        assertEquals(PlayerStats.restored(playerId, 4, 2), stats.recordWin())
        assertEquals(PlayerStats.restored(playerId, 3, 3), stats.recordLoss())
    }

    @Test
    fun `ratio uses one as the denominator when there are no losses`() {
        assertEquals(3.0, PlayerStats.restored(playerId, 3, 0).ratio)
        assertEquals(1.5, PlayerStats.restored(playerId, 3, 2).ratio)
    }
}
