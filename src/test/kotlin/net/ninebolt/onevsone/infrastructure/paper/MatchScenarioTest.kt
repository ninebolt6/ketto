package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.BlockPosition
import net.ninebolt.onevsone.infrastructure.paper.fixtures.TestEnv
import net.ninebolt.onevsone.infrastructure.paper.fixtures.attackDamage
import net.ninebolt.onevsone.infrastructure.paper.fixtures.interact
import net.ninebolt.onevsone.infrastructure.paper.fixtures.signBlock
import net.ninebolt.onevsone.infrastructure.paper.fixtures.twoPlayerIngame
import net.ninebolt.onevsone.infrastructure.paper.fixtures.uuid
import org.bukkit.Material
import org.junit.jupiter.api.AfterEach
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * End-to-end verification of user journeys (sign join -> finish,
 * disconnect -> rejoin) through real actions. requiredWins=1 shortens matches
 * to a single round; exhaustive coverage of each mechanism lives in the
 * listener tests.
 */
class MatchScenarioTest {

    @TempDir
    lateinit var folder: File

    private lateinit var env: TestEnv

    @BeforeEach
    fun setup() {
        env = TestEnv(folder, requiredWins = 1)
    }

    @AfterEach
    fun tearDown() {
        env.close()
    }

    @Test
    fun `sign click to match end`() {
        val arena = env.newArena()
        env.signRepo.setSign("arena1", BlockPosition.new("world", 3, 64, 3))
        env.setKit(arena, PaperInventorySnapshot(items = listOf(env.item(Material.IRON_SWORD))))
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")

        env.fire(interact(p1, env.signBlock(3, 64, 3)))
        assertEquals(ArenaState.ONEMORE, env.state())
        env.fire(interact(p2, env.signBlock(3, 64, 3)))
        assertEquals(ArenaState.COUNTDOWN, env.state())

        env.tick(6)
        assertEquals(ArenaState.INGAME, env.state())
        assertTrue(p1.hasTeleported())
        assertEquals(Material.IRON_SWORD, p1.inventory.contents[0]?.type)

        p2.simulateDamage(100.0, attackDamage(p1))
        assertEquals(ArenaState.WAITING, env.state())
        assertEquals(1, env.statsRepo.find(p1.uuid)!!.wins)
        assertEquals(1, env.statsRepo.find(p2.uuid)!!.losses)
        // Match end restores the pre-join empty inventory
        assertNull(p1.inventory.contents[0])
    }

    @Test
    fun `countdown disconnect unregisters only`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)
        assertEquals(ArenaState.COUNTDOWN, env.state())

        p2.disconnect()
        // A pre-start disconnect unregisters rather than defeats; the other participant returns to waiting, no stats recorded
        assertEquals(ArenaState.ONEMORE, env.state())
        assertNull(env.service.arenaIdOf(p2.uuid))
        assertNull(env.statsRepo.find(p1.uuid))
        assertNull(env.statsRepo.find(p2.uuid))
    }

    @Test
    fun `ingame disconnect forfeits and reconnect restores inventory`() {
        val (p1, p2) = env.twoPlayerIngame()
        p1.disconnect()
        assertEquals(ArenaState.WAITING, env.state())
        assertEquals(1, env.statsRepo.find(p2.uuid)!!.wins)
        assertEquals(1, env.statsRepo.find(p1.uuid)!!.losses)

        p1.reconnect()
        assertNull(p1.inventory.contents[0])
    }
}
