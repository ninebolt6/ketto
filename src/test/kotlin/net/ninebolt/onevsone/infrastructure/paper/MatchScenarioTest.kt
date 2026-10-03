package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.BlockPosition
import net.ninebolt.onevsone.domain.fixtures.arenaId
import net.ninebolt.onevsone.infrastructure.paper.fixtures.TestEnv
import net.ninebolt.onevsone.infrastructure.paper.fixtures.attackDamage
import net.ninebolt.onevsone.infrastructure.paper.fixtures.interact
import net.ninebolt.onevsone.infrastructure.paper.fixtures.signBlock
import net.ninebolt.onevsone.infrastructure.paper.fixtures.uuid
import org.bukkit.Material
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        env.signRepo.setSign(arenaId("arena1"), BlockPosition.new("world", 3, 64, 3))
        env.setKit(arena, PaperInventorySnapshot(items = listOf(env.item(Material.IRON_SWORD))))
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")

        env.fire(interact(p1, env.signBlock(3, 64, 3)))
        assertEquals(ArenaState.Kind.ONEMORE, env.state())
        env.fire(interact(p2, env.signBlock(3, 64, 3)))
        assertEquals(ArenaState.Kind.COUNTDOWN, env.state())

        env.tick(6)
        assertEquals(ArenaState.Kind.INGAME, env.state())
        assertTrue(p1.hasTeleported())
        assertEquals(Material.IRON_SWORD, p1.inventory.contents[0]?.type)

        p2.simulateDamage(100.0, attackDamage(p1))
        assertEquals(ArenaState.Kind.WAITING, env.state())
        assertEquals(1, env.statsRepo.find(p1.uuid)!!.wins)
        assertEquals(1, env.statsRepo.find(p2.uuid)!!.losses)
        assertNull(p1.inventory.contents[0])
    }
}
