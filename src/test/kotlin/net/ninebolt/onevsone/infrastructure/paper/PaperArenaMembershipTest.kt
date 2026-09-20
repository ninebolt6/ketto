package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.application.LeaveReply
import net.ninebolt.onevsone.application.ToggleReply
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.DefeatCause
import net.ninebolt.onevsone.infrastructure.paper.fixtures.TestEnv
import net.ninebolt.onevsone.infrastructure.paper.fixtures.drainMessages
import net.ninebolt.onevsone.infrastructure.paper.fixtures.lastBroadcast
import net.ninebolt.onevsone.infrastructure.paper.fixtures.playersYaml
import net.ninebolt.onevsone.infrastructure.paper.fixtures.uuid
import net.ninebolt.onevsone.infrastructure.paper.fixtures.view
import org.bukkit.Material
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** 退出・切断・有効化/停止・メンバーシップ登録のシナリオ。 */
class PaperArenaMembershipTest {

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
    fun `quit during INGAME forfeits with stats and restores both`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        p1.inventory.setItem(0, env.item(Material.BREAD))
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)

        env.removePlayer(p2)
        env.quit(p2)

        assertEquals(ArenaState.WAITING, env.view().state)
        assertNull(env.service.arenaIdOf(p1.uuid))
        assertNull(env.service.arenaIdOf(p2.uuid))
        assertEquals(1, env.statsRepo.find(p1.uuid)!!.wins)
        assertEquals(1, env.statsRepo.find(p2.uuid)!!.losses)
        assertEquals(Material.BREAD, p1.inventory.contents[0]?.type)
        assertTrue(env.lastBroadcast().contains("Alice"))
    }

    @Test
    fun `quit during COUNTDOWN forfeits`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)
        env.removePlayer(p1)
        env.quit(p1)
        assertEquals(ArenaState.WAITING, env.view().state)
        assertEquals(1, env.statsRepo.find(p2.uuid)!!.wins)
        assertEquals(1, env.statsRepo.find(p1.uuid)!!.losses)
    }

    @Test
    fun `quit during ONEMORE unregisters without stats`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        p1.inventory.setItem(0, env.item(Material.BREAD))
        env.join(p1, arena)
        env.removePlayer(p1)
        env.quit(p1)
        assertEquals(ArenaState.WAITING, env.view().state)
        assertNull(env.service.arenaIdOf(p1.uuid))
        assertNull(env.statsRepo.find(p1.uuid))
        assertEquals(Material.BREAD, p1.inventory.contents[0]?.type)
    }

    @Test
    fun `leave only allowed in ONEMORE`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        assertEquals(LeaveReply.NotJoined, env.leave(p1))
        assertTrue(p1.drainMessages().any { it.contains("あなたはアリーナに参加していません！") })

        env.join(p1, arena)
        env.join(p2, arena)
        assertEquals(LeaveReply.NotWaiting, env.leave(p1))
        assertTrue(p1.drainMessages().any { it.contains("カウントダウン中はアリーナから退出できません！") })
        assertEquals(arena, env.service.arenaIdOf(p1.uuid))
    }

    @Test
    fun `leave in ONEMORE resets arena without touching inventory`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        p1.inventory.setItem(0, env.item(Material.BREAD))
        env.join(p1, arena)
        assertEquals(LeaveReply.Left, env.leave(p1))
        assertTrue(p1.drainMessages().any { it.contains("アリーナから退出しました") })
        assertEquals(ArenaState.WAITING, env.view().state)
        assertNull(env.service.arenaIdOf(p1.uuid))
        assertEquals(Material.BREAD, p1.inventory.contents[0]?.type)
    }

    @Test
    fun `quit during ROUNDCOUNTDOWN forfeits with stats`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)
        env.service.defeat(p2.uuid, DefeatCause.FALL)
        assertEquals(ArenaState.ROUNDCOUNTDOWN, env.view().state)

        env.removePlayer(p1)
        env.quit(p1)
        assertEquals(ArenaState.WAITING, env.view().state)
        assertEquals(1, env.statsRepo.find(p2.uuid)!!.wins)
        assertEquals(1, env.statsRepo.find(p1.uuid)!!.losses)
    }

    @Test
    fun `abort clears sidebar`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)
        // INGAME でサイドバー用ボードが割り当てられていること
        val ingameBoard = p1.scoreboard
        assertTrue(env.boards.contains(ingameBoard))
        env.service.abort(arena)
        assertNotSame(ingameBoard, p1.scoreboard)
        assertNotSame(ingameBoard, p2.scoreboard)
    }

    @Test
    fun `createArena rejects case insensitive duplicates`() {
        assertTrue(env.admin.create("Arena1"))
        assertFalse(env.admin.create("arena1"))
        assertFalse(env.admin.create("PLAYERS"))
    }

    @Test
    fun `join writes membership only and never reads waiting inventory`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        env.join(p1, arena)
        assertNull(p1.inventory.contents[0])
        val yaml = env.playersYaml()
        assertTrue(yaml.getStringList("players").contains("Alice"))
        assertEquals("arena1", yaml.getString("arena.Alice"))
        assertNull(yaml.getConfigurationSection("inv.Alice"))
    }

    @Test
    fun `waiting leave does not recreate transferred items and keeps received items`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        p1.inventory.setItem(0, env.item(Material.DIAMOND))
        env.join(p1, arena)
        p1.inventory.setItem(0, null)
        env.leave(p1)
        assertNull(p1.inventory.contents[0])

        val p2 = env.player("Bob")
        env.join(p2, arena)
        p2.inventory.setItem(0, env.item(Material.APPLE))
        env.leave(p2)
        assertEquals(Material.APPLE, p2.inventory.contents[0]?.type)
    }

    @Test
    fun `waiting quit disable and shutdown preserve current inventory`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        p1.inventory.setItem(0, env.item(Material.BREAD))
        env.join(p1, arena)
        env.removePlayer(p1)
        env.quit(p1)
        assertEquals(Material.BREAD, p1.inventory.contents[0]?.type)
        assertNull(env.service.pendingRestore(p1.uuid))

        val p2 = env.player("Bob")
        p2.inventory.setItem(0, env.item(Material.APPLE))
        env.join(p2, arena)
        env.admin.setEnabled("arena1", false)
        assertEquals(Material.APPLE, p2.inventory.contents[0]?.type)
        assertNull(env.service.pendingRestore(p2.uuid))
        env.admin.setEnabled("arena1", true)

        val p3 = env.player("Carol")
        p3.inventory.setItem(0, env.item(Material.COOKED_BEEF))
        env.join(p3, arena)
        env.service.shutdown()
        assertEquals(Material.COOKED_BEEF, p3.inventory.contents[0]?.type)
    }

    @Test
    fun `quit during COUNTDOWN forfeits without touching inventories`() {
        val arena = env.newArena()
        env.setKit(arena, PaperInventorySnapshot(items = listOf(env.item(Material.IRON_SWORD))))
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        p1.inventory.setItem(0, env.item(Material.BREAD))
        p2.inventory.setItem(0, env.item(Material.APPLE))
        env.join(p1, arena)
        env.join(p2, arena)
        env.removePlayer(p1)
        env.quit(p1)
        assertEquals(ArenaState.WAITING, env.view().state)
        assertEquals(Material.BREAD, p1.inventory.contents[0]?.type)
        assertEquals(Material.APPLE, p2.inventory.contents[0]?.type)
        assertEquals(1, env.statsRepo.find(p2.uuid)!!.wins)
        assertEquals(1, env.statsRepo.find(p1.uuid)!!.losses)
        val yaml = env.playersYaml()
        assertNull(yaml.getConfigurationSection("inv.Alice"))
        assertNull(yaml.getConfigurationSection("inv.Bob"))
    }

    @Test
    fun `shutdown preserves enabled and clears state`() {
        env.newArena()
        val p1 = env.player("Alice")
        env.join(p1, Arena.Id.new("arena1"))
        env.service.shutdown()
        assertTrue(env.service.arena("arena1")!!.enabled)
        assertEquals(ArenaState.WAITING, env.view().state)
        assertTrue(env.view().participants.isEmpty())
        assertNull(env.service.arenaIdOf(p1.uuid))
    }

    @Test
    fun `disable aborts and clears registration while persisting disabled`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)
        assertEquals(ToggleReply.Changed, env.admin.setEnabled("arena1", false))
        assertFalse(env.service.arena("arena1")!!.enabled)
        assertEquals(ArenaState.WAITING, env.view().state)
        assertNull(env.service.arenaIdOf(p1.uuid))
        assertNull(env.service.arenaIdOf(p2.uuid))
        env.tick(6)
        assertFalse(p1.hasTeleported())
        val reloaded = env.arenaRepo.find("arena1")!!
        assertFalse(reloaded.enabled)
    }

    @Test
    fun `enabled persists across service load`() {
        env.arenaRepo.save(Arena.new(Arena.Id.new("arena1"), enabled = true))
        env.service.load()
        assertTrue(env.service.arena("arena1")!!.enabled)
    }
}
