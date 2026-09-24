package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.infrastructure.paper.fixtures.TestEnv
import net.ninebolt.onevsone.infrastructure.paper.fixtures.fallIntoVoid
import net.ninebolt.onevsone.infrastructure.paper.fixtures.breakBlock
import net.ninebolt.onevsone.infrastructure.paper.fixtures.drainMessages
import net.ninebolt.onevsone.infrastructure.paper.fixtures.dropEvent
import net.ninebolt.onevsone.infrastructure.paper.fixtures.genericDamage
import net.ninebolt.onevsone.infrastructure.paper.fixtures.plainBlock
import net.ninebolt.onevsone.infrastructure.paper.fixtures.placeBlock
import net.ninebolt.onevsone.infrastructure.paper.fixtures.simulation
import net.ninebolt.onevsone.infrastructure.paper.fixtures.uuid
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.junit.jupiter.api.AfterEach
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ArenaListenerRestrictionTest {

    @TempDir
    lateinit var folder: File

    private lateinit var env: TestEnv

    @BeforeEach
    fun setup() {
        env = TestEnv(folder)
    }

    @AfterEach
    fun tearDown() {
        env.close()
    }

    // MockBukkit's performCommand never fires the preprocess event, so dispatch a real one
    private fun assertCommandBlocked(player: Player, blocked: Boolean) {
        val event = PlayerCommandPreprocessEvent(player, "/spawn")
        env.fire(event)
        assertEquals(blocked, event.isCancelled)
    }

    @Test
    fun `commands blocked except in ONEMORE`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")

        assertCommandBlocked(p1, blocked = false)

        env.join(p1, arena)
        assertCommandBlocked(p1, blocked = false)

        val p2 = env.player("Bob")
        env.join(p2, arena)
        assertCommandBlocked(p1, blocked = true)
        assertTrue(p1.drainMessages().any { it.contains("コマンドは使用できません！") })
    }

    @Test
    fun `item drop cancelled only while equipped`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        env.join(p1, arena)
        val onemore = env.dropEvent(p1)
        env.fire(onemore)
        assertFalse(onemore.isCancelled)

        val p2 = env.player("Bob")
        env.join(p2, arena)
        val countdown = env.dropEvent(p1)
        env.fire(countdown)
        assertFalse(countdown.isCancelled)

        env.tick(6)
        val ingame = env.dropEvent(p1)
        env.fire(ingame)
        assertTrue(ingame.isCancelled)

        fallIntoVoid(p2)
        val roundCountdown = env.dropEvent(p1)
        env.fire(roundCountdown)
        assertTrue(roundCountdown.isCancelled)

        val outsider = env.player("Carol")
        val free = env.dropEvent(outsider)
        env.fire(free)
        assertFalse(free.isCancelled)
    }

    @Test
    fun `block place cancelled only while equipped except flint and steel`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        env.join(p1, arena)
        val sim = p1.simulation()
        var placeZ = 10
        fun place() = sim.placeBlock(Material.STONE, Location(env.world(), 9.0, 64.0, (placeZ++).toDouble()))

        assertFalse(place().isCancelled)

        val p2 = env.player("Bob")
        env.join(p2, arena)
        assertFalse(place().isCancelled)

        env.tick(6)
        assertTrue(place().isCancelled)

        // simulateBlockPlace uses the item in hand from the real inventory
        p1.inventory.setItem(p1.inventory.heldItemSlot, env.item(Material.FLINT_AND_STEEL))
        assertFalse(place().isCancelled)
        p1.inventory.clear(p1.inventory.heldItemSlot)

        fallIntoVoid(p2)
        assertTrue(place().isCancelled)
    }

    @Test
    fun `restriction matrix follows live state transitions`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        env.join(p1, arena)
        val sim = p1.simulation()
        var breakZ = 20

        fun assertState(damageCancelled: Boolean, breakCancelled: Boolean, commandBlocked: Boolean) {
            val damage = p1.simulateDamage(1.0, genericDamage())
            assertEquals(damageCancelled, damage.isCancelled)

            val breaking = sim.breakBlock(env.plainBlock(9, 64, breakZ++))
            assertEquals(breakCancelled, breaking.isCancelled)

            assertCommandBlocked(p1, commandBlocked)
        }

        assertState(damageCancelled = false, breakCancelled = false, commandBlocked = false)

        val p2 = env.player("Bob")
        env.join(p2, arena)
        assertState(damageCancelled = false, breakCancelled = false, commandBlocked = true)

        env.tick(6)
        assertState(damageCancelled = false, breakCancelled = true, commandBlocked = true)

        fallIntoVoid(p2)
        assertState(damageCancelled = true, breakCancelled = true, commandBlocked = true)
    }
}
