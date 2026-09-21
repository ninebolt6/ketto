package net.ninebolt.onevsone.infrastructure.paper

import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.DefeatCause
import net.ninebolt.onevsone.infrastructure.paper.fixtures.TestEnv
import net.ninebolt.onevsone.infrastructure.paper.fixtures.containsText
import net.ninebolt.onevsone.infrastructure.paper.fixtures.uuid
import net.ninebolt.onevsone.infrastructure.paper.fixtures.view
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.junit.jupiter.api.AfterEach
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.util.logging.Level
import java.util.logging.Logger

/** 永続化・戦績・登録解除の失敗注入シナリオ。 */
class PaperArenaFailureTest {

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

    @Test
    fun `join refused when participant snapshot cannot be persisted`() {
        env.close()
        val broken = File(folder, "broken")
        broken.mkdirs()
        File(broken, "status").writeText("not a directory")
        env = TestEnv(broken)
        val arena = env.newArena()
        val p = env.player("Alice")
        assertFailsWith<IllegalStateException> {
            env.service.join(p.uuid, p.name, arena)
        }
        assertNull(env.service.arenaIdOf(p.uuid))
        assertTrue(env.view().participants.isEmpty())
        assertEquals(ArenaState.WAITING, env.view().state)
    }

    @Test
    fun `malformed winner stats does not prevent final death cleanup`() {
        env.close()
        env = TestEnv(folder, requiredWins = 1)
        val logger = mockk<Logger>(relaxed = true)
        every { env.plugin.logger } returns logger
        val arena = env.newArena()
        env.setKit(arena, PaperInventorySnapshot(items = listOf(env.item(Material.IRON_SWORD))))
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        p1.inventory.setItem(0, env.item(Material.BREAD))
        p2.inventory.setItem(0, env.item(Material.APPLE))
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)
        val winnerStats = File(folder, "stats/${p1.uuid}.yml")
        winnerStats.writeText("win: [broken")
        p2.health = 0.0

        assertTrue(env.service.defeat(p2.uuid, DefeatCause.DEATH))
        assertEquals(ArenaState.WAITING, env.view().state)
        assertEquals(Material.BREAD, p1.inventory.contents[0]?.type)
        env.runOneShots()
        assertEquals(Material.APPLE, p2.inventory.contents[0]?.type)
        assertNull(env.service.arenaIdOf(p1.uuid))
        assertNull(env.service.arenaIdOf(p2.uuid))
        assertTrue(env.boards.contains(p1.scoreboard))
        assertEquals("win: [broken", winnerStats.readText())
        assertEquals(1, env.statsRepo.find(p2.uuid)!!.losses)
        verify(exactly = 1) {
            logger.log(
                eq(Level.SEVERE),
                containsText("Failed to record"),
                any<Throwable>()
            )
        }
        val statusYaml = YamlConfiguration.loadConfiguration(File(folder, "status/arena1.yml"))
        assertEquals("WAITING", statusYaml.getString("status"))
        assertTrue(statusYaml.getStringList("players").isEmpty())
    }

    @Test
    fun `malformed loser stats does not prevent final cleanup`() {
        env.close()
        env = TestEnv(folder, requiredWins = 1)
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        p1.inventory.setItem(0, env.item(Material.BREAD))
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)
        File(folder, "stats/${p2.uuid}.yml").writeText("lose: [broken")

        assertTrue(env.service.defeat(p2.uuid, DefeatCause.FALL))
        assertEquals(ArenaState.WAITING, env.view().state)
        assertEquals(Material.BREAD, p1.inventory.contents[0]?.type)
        assertEquals(1, env.statsRepo.find(p1.uuid)!!.wins)
    }

    @Test
    fun `stats write failure does not prevent final cleanup`() {
        env.close()
        env = TestEnv(folder, requiredWins = 1)
        val spyStats = spyk(env.statsRepo)
        env.rebuildWith(statsRepo = spyStats)
        val arena = env.newArena("spy-arena", enabled = true)
        env.setKit(arena, PaperInventorySnapshot(items = listOf(env.item(Material.IRON_SWORD))))
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        p1.inventory.setItem(0, env.item(Material.BREAD))
        p2.inventory.setItem(0, env.item(Material.APPLE))
        env.service.join(p1.uuid, p1.name, arena)
        env.service.join(p2.uuid, p2.name, arena)
        val winnerId = p1.uuid
        every { spyStats.recordWin(winnerId) } throws IllegalStateException("write failed", IOException("disk full"))
        env.tick(6)

        assertTrue(env.service.defeat(p2.uuid, DefeatCause.FALL))
        assertEquals(ArenaState.WAITING, env.service.matchOf("spy-arena")!!.state)
        assertEquals(Material.BREAD, p1.inventory.contents[0]?.type)
        assertEquals(Material.APPLE, p2.inventory.contents[0]?.type)
        assertNull(env.service.arenaIdOf(p1.uuid))
        assertNull(env.service.arenaIdOf(p2.uuid))
        assertEquals(1, env.statsRepo.find(p2.uuid)!!.losses)
        assertFalse(File(folder, "stats/${p1.uuid}.yml").exists())
    }

    @Test
    fun `stats failure during INGAME forfeit still completes cleanup`() {
        env.close()
        env = TestEnv(folder)
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        p1.inventory.setItem(0, env.item(Material.BREAD))
        p2.inventory.setItem(0, env.item(Material.APPLE))
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)
        File(folder, "stats/${p1.uuid}.yml").writeText("lose: [broken")
        env.removePlayer(p1)

        env.quit(p1)
        assertEquals(ArenaState.WAITING, env.view().state)
        assertNull(env.service.arenaIdOf(p2.uuid))
        assertEquals(Material.BREAD, p1.inventory.contents[0]?.type)
        assertEquals(Material.APPLE, p2.inventory.contents[0]?.type)
        assertEquals(1, env.statsRepo.find(p2.uuid)!!.wins)
    }
}
