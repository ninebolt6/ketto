package net.ninebolt.ketto.domain

import net.ninebolt.ketto.domain.fixtures.arenaId
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ArenaTest {

    private val id = arenaId("arena1")
    private val pos1 = WorldPosition.new("world", 1.0, 64.0, 1.0)
    private val pos2 = WorldPosition.new("world", 2.0, 64.0, 2.0)

    @Test
    fun `disabled arena reports missing spawns in slot order`() {
        val arena = Arena.Disabled.new(id)
        assertEquals(listOf(SpawnSlot.FIRST, SpawnSlot.SECOND), arena.missingSpawns)
        assertEquals(
            EnableOutcome.MissingSpawns(listOf(SpawnSlot.FIRST, SpawnSlot.SECOND)),
            arena.enable(),
        )
    }

    @Test
    fun `partially configured arena reports only the missing slot`() {
        val arena = Arena.Disabled.new(id).withSpawn(SpawnSlot.FIRST, pos1)
        assertEquals(listOf(SpawnSlot.SECOND), arena.missingSpawns)
        assertEquals(EnableOutcome.MissingSpawns(listOf(SpawnSlot.SECOND)), arena.enable())
    }

    @Test
    fun `fully configured arena enables and keeps spawns non null`() {
        val arena = Arena.Disabled.new(id)
            .withSpawn(SpawnSlot.FIRST, pos1)
            .withSpawn(SpawnSlot.SECOND, pos2)
        val outcome = arena.enable()
        val enabled = assertIs<EnableOutcome.Ready>(outcome).arena
        assertTrue(arena.missingSpawns.isEmpty())
        assertEquals(pos1, enabled.spawn(SpawnSlot.FIRST))
        assertEquals(pos2, enabled.spawn(SpawnSlot.SECOND))
        assertTrue(enabled.enabled)
    }

    @Test
    fun `disable keeps spawns so the arena can be re-enabled`() {
        val enabled = Arena.Enabled.restored(id, pos1, pos2)
        val disabled = enabled.disable()
        assertEquals(pos1, disabled.spawn1)
        assertEquals(pos2, disabled.spawn2)
        assertIs<EnableOutcome.Ready>(disabled.enable())
    }

    @Test
    fun `withSpawn on an enabled arena stays enabled`() {
        val enabled = Arena.Enabled.restored(id, pos1, pos2)
        val moved = WorldPosition.new("world", 5.0, 70.0, -2.5)
        val updated = enabled.withSpawn(SpawnSlot.SECOND, moved)
        assertEquals(moved, updated.spawn(SpawnSlot.SECOND))
        assertEquals(pos1, updated.spawn(SpawnSlot.FIRST))
        assertTrue(updated.enabled)
    }
}
