package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.infrastructure.paper.fixtures.TestEnv
import net.ninebolt.onevsone.infrastructure.paper.fixtures.backupByName
import net.ninebolt.onevsone.infrastructure.paper.fixtures.fallIntoVoid
import net.ninebolt.onevsone.infrastructure.paper.fixtures.lastBroadcast
import net.ninebolt.onevsone.infrastructure.paper.fixtures.opPlayer
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
        assertNull(env.recovery.findPending(p1.uuid))

        val p2 = env.player("Bob")
        p2.inventory.setItem(0, env.item(Material.APPLE))
        env.join(p2, arena)
        env.administration.disable("arena1")
        assertEquals(Material.APPLE, p2.inventory.contents[0]?.type)
        assertNull(env.recovery.findPending(p2.uuid))
        env.administration.enable("arena1")

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
        assertEquals(ArenaState.Kind.ONEMORE, env.view().state.kind)
        assertNull(env.sessions.findArenaIdOf(p1.uuid))
        assertEquals(arena, env.sessions.findArenaIdOf(p2.uuid))
        assertEquals(Material.BREAD, p1.inventory.contents[0]?.type)
        assertEquals(Material.APPLE, p2.inventory.contents[0]?.type)
        assertNull(env.statsRepository.find(p2.uuid))
        assertNull(env.statsRepository.find(p1.uuid))
        assertNull(env.backupByName("Alice"))
        assertNull(env.backupByName("Bob"))

        env.tick(6)
        assertEquals(ArenaState.Kind.ONEMORE, env.view().state.kind)
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
        assertEquals(ArenaState.Kind.ROUNDCOUNTDOWN, env.view().state.kind)

        p1.disconnect()
        assertNull(env.recovery.findPending(p1.uuid))
        assertNull(env.backupByName("Alice"))
        assertEquals(ArenaState.Kind.WAITING, env.view().state.kind)
        assertTrue(env.view().participants.isEmpty())
        assertNull(env.sessions.findArenaIdOf(p1.uuid))
        assertNull(env.sessions.findArenaIdOf(p2.uuid))
        assertEquals(1, env.statsRepository.find(p2.uuid)!!.wins)
        assertEquals(1, env.statsRepository.find(p1.uuid)!!.losses)
        assertEquals(Material.APPLE, p2.inventory.contents[0]?.type)
        assertTrue(env.lastBroadcast().contains("Bob"))

        p1.reconnect()
        assertEquals(Material.BREAD, p1.inventory.contents[0]?.type)

        // The round-resume timer was still pending at the quit and must not restart the finished match
        env.tick(8)
        assertEquals(ArenaState.Kind.WAITING, env.view().state.kind)
    }

    @Test
    fun `disconnect without a quit event during countdown aborts the match`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        p1.inventory.setItem(0, env.item(Material.BREAD))
        env.join(p1, arena)
        env.join(p2, arena)
        assertEquals(ArenaState.Kind.COUNTDOWN, env.view().state.kind)

        env.disconnectWithoutQuitHandler(p2)
        env.tick()

        assertEquals(ArenaState.Kind.WAITING, env.view().state.kind)
        assertNull(env.sessions.findArenaIdOf(p1.uuid))
        assertNull(env.sessions.findArenaIdOf(p2.uuid))
        assertEquals(Material.BREAD, p1.inventory.contents[0]?.type)
        assertNull(env.backupByName("Alice"))
    }

    @Test
    fun `recreated arena does not reuse removed kit`() {
        val arena = env.newArena()
        env.setKit(arena, PaperInventorySnapshot(items = listOf(env.item(Material.IRON_SWORD))))
        env.runCommand(env.opPlayer("Op"), "arena", "arena1", "remove")
        assertEquals(PaperInventorySnapshot(), env.kitStore.loadArenaKit(arena.name))

        env.newArena("arena1")
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)
        assertNull(p1.inventory.contents[0])
    }
}
