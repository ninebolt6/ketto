package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.WorldPosition
import net.ninebolt.onevsone.infrastructure.paper.fixtures.TestEnv
import net.ninebolt.onevsone.infrastructure.paper.fixtures.backupByName
import net.ninebolt.onevsone.infrastructure.paper.fixtures.fallIntoVoid
import net.ninebolt.onevsone.infrastructure.paper.fixtures.lastBroadcast
import net.ninebolt.onevsone.infrastructure.paper.fixtures.registrations
import net.ninebolt.onevsone.infrastructure.paper.fixtures.runCommand
import net.ninebolt.onevsone.infrastructure.paper.fixtures.uuid
import net.ninebolt.onevsone.infrastructure.paper.fixtures.view
import org.bukkit.Material
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

        p2.disconnect()

        assertEquals(ArenaState.WAITING, env.view().state)
        assertNull(env.service.arenaIdOf(p1.uuid))
        assertNull(env.service.arenaIdOf(p2.uuid))
        assertEquals(1, env.statsRepo.find(p1.uuid)!!.wins)
        assertEquals(1, env.statsRepo.find(p2.uuid)!!.losses)
        assertEquals(Material.BREAD, p1.inventory.contents[0]?.type)
        assertTrue(env.lastBroadcast().contains("Alice"))
    }

    @Test
    fun `abort clears sidebar`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)
        val ingameBoard = p1.scoreboard
        assertTrue(env.boards.contains(ingameBoard))
        env.service.abort(arena)
        assertNotSame(ingameBoard, p1.scoreboard)
        assertNotSame(ingameBoard, p2.scoreboard)
    }

    @Test
    fun `join writes membership only and never reads waiting inventory`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        env.join(p1, arena)
        assertNull(p1.inventory.contents[0])
        val registration = env.registrations().single { it.playerName == "Alice" }
        assertEquals(p1.uniqueId.toString(), registration.playerUuid)
        assertEquals("arena1", registration.arenaName)
        assertNull(env.backupByName("Alice"))
    }

    @Test
    fun `waiting leave does not recreate transferred items and keeps received items`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        p1.inventory.setItem(0, env.item(Material.DIAMOND))
        env.join(p1, arena)
        p1.inventory.setItem(0, null)
        env.runCommand(p1, "leave")
        assertNull(p1.inventory.contents[0])

        val p2 = env.player("Bob")
        env.join(p2, arena)
        p2.inventory.setItem(0, env.item(Material.APPLE))
        env.runCommand(p2, "leave")
        assertEquals(Material.APPLE, p2.inventory.contents[0]?.type)
    }

    @Test
    fun `waiting quit disable and shutdown preserve current inventory`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        p1.inventory.setItem(0, env.item(Material.BREAD))
        env.join(p1, arena)
        p1.disconnect()
        assertEquals(Material.BREAD, p1.inventory.contents[0]?.type)
        assertNull(env.service.pendingRestore(p1.uuid))

        val p2 = env.player("Bob")
        p2.inventory.setItem(0, env.item(Material.APPLE))
        env.join(p2, arena)
        env.admin.disable("arena1")
        assertEquals(Material.APPLE, p2.inventory.contents[0]?.type)
        assertNull(env.service.pendingRestore(p2.uuid))
        env.admin.enable("arena1")

        val p3 = env.player("Carol")
        p3.inventory.setItem(0, env.item(Material.COOKED_BEEF))
        env.join(p3, arena)
        env.lifecycle.shutdown()
        assertEquals(Material.COOKED_BEEF, p3.inventory.contents[0]?.type)
    }

    @Test
    fun `quit during COUNTDOWN unregisters only and keeps opponent waiting`() {
        val arena = env.newArena()
        env.setKit(arena, PaperInventorySnapshot(items = listOf(env.item(Material.IRON_SWORD))))
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        p1.inventory.setItem(0, env.item(Material.BREAD))
        p2.inventory.setItem(0, env.item(Material.APPLE))
        env.join(p1, arena)
        env.join(p2, arena)
        p1.disconnect()
        assertEquals(ArenaState.ONEMORE, env.view().state)
        assertNull(env.service.arenaIdOf(p1.uuid))
        assertEquals(arena, env.service.arenaIdOf(p2.uuid))
        assertEquals(Material.BREAD, p1.inventory.contents[0]?.type)
        assertEquals(Material.APPLE, p2.inventory.contents[0]?.type)
        assertNull(env.statsRepo.find(p2.uuid))
        assertNull(env.statsRepo.find(p1.uuid))
        assertNull(env.backupByName("Alice"))
        assertNull(env.backupByName("Bob"))

        env.tick(6)
        assertEquals(ArenaState.ONEMORE, env.view().state)
        assertFalse(p1.hasTeleported())
        assertFalse(p2.hasTeleported())
    }

    @Test
    fun `quit during ROUNDCOUNTDOWN forfeits and the round resume never fires`() {
        val arena = env.newArena()
        env.setKit(arena, PaperInventorySnapshot(items = listOf(env.item(Material.IRON_SWORD))))
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        p1.inventory.setItem(0, env.item(Material.BREAD))
        p2.inventory.setItem(0, env.item(Material.APPLE))
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)

        fallIntoVoid(p2)
        assertEquals(ArenaState.ROUNDCOUNTDOWN, env.view().state)

        p1.disconnect()
        assertNull(env.service.pendingRestore(p1.uuid))
        assertNull(env.backupByName("Alice"))
        assertEquals(ArenaState.WAITING, env.view().state)
        assertTrue(env.view().participants.isEmpty())
        assertNull(env.service.arenaIdOf(p1.uuid))
        assertNull(env.service.arenaIdOf(p2.uuid))
        assertEquals(1, env.statsRepo.find(p2.uuid)!!.wins)
        assertEquals(1, env.statsRepo.find(p1.uuid)!!.losses)
        assertEquals(Material.APPLE, p2.inventory.contents[0]?.type)
        assertTrue(env.lastBroadcast().contains("Bob"))

        p1.reconnect()
        assertEquals(Material.BREAD, p1.inventory.contents[0]?.type)

        // The round-resume timer was still pending at the quit and must not restart the finished match
        env.tick(8)
        assertEquals(ArenaState.WAITING, env.view().state)
    }

    @Test
    fun `recreated arena does not reuse removed kit`() {
        val arena = env.newArena()
        env.setKit(arena, PaperInventorySnapshot(items = listOf(env.item(Material.IRON_SWORD))))
        env.admin.remove("arena1")
        assertNull(env.equipment.kitOf(arena))

        env.newArena("arena1")
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)
        assertNull(p1.inventory.contents[0])
    }

    @Test
    fun `enabled persists across service load`() {
        env.arenaRepo.save(
            Arena.Enabled.restored(
                Arena.Id.new("arena1"),
                WorldPosition.new("world", 1.0, 64.0, 1.0),
                WorldPosition.new("world", 2.0, 64.0, 2.0),
            ),
        )
        env.lifecycle.load()
        assertTrue(env.service.arena("arena1")!!.enabled)
    }
}
