package net.ninebolt.onevsone.infrastructure.paper

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.BlockPosition
import net.ninebolt.onevsone.domain.fixtures.arenaId
import net.ninebolt.onevsone.infrastructure.paper.fixtures.TestEnv
import net.ninebolt.onevsone.infrastructure.paper.fixtures.attackDamage
import net.ninebolt.onevsone.infrastructure.paper.fixtures.backupByName
import net.ninebolt.onevsone.infrastructure.paper.fixtures.drainMessages
import net.ninebolt.onevsone.infrastructure.paper.fixtures.fallIntoVoid
import net.ninebolt.onevsone.infrastructure.paper.fixtures.interact
import net.ninebolt.onevsone.infrastructure.paper.fixtures.opPlayer
import net.ninebolt.onevsone.infrastructure.paper.fixtures.runCommand
import net.ninebolt.onevsone.infrastructure.paper.fixtures.signBlock
import net.ninebolt.onevsone.infrastructure.paper.fixtures.uuid
import net.ninebolt.onevsone.infrastructure.persistence.SqliteStore
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.Sign
import org.bukkit.block.sign.Side
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MatchScenarioTest {

    @TempDir
    lateinit var folder: File

    private lateinit var env: TestEnv

    private val plain = PlainTextComponentSerializer.plainText()

    @BeforeEach
    fun setup() {
        env = TestEnv(folder, requiredWins = 1)
    }

    @AfterEach
    fun tearDown() {
        env.close()
    }

    private fun signLines(block: Block) = (0..3).map {
        plain.serialize((block.state as Sign).getSide(Side.FRONT).line(it))
    }

    @Test
    fun `sign click to match end`() {
        val arena = env.newArena()
        env.signRepository.setSign(arenaId("arena1"), BlockPosition.new("world", 3, 64, 3))
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
        assertEquals(1, env.statsRepository.find(p1.uuid)!!.wins)
        assertEquals(1, env.statsRepository.find(p2.uuid)!!.losses)
        assertNull(p1.inventory.contents[0])
    }

    @Test
    fun `command setup through a finished match returns players to the lobby and frees a rematch`() {
        val op = env.opPlayer("Op")
        op.setLocation(Location(env.world(), 100.0, 64.0, 100.0))
        env.runCommand(op, "lobby", "set")

        env.runCommand(op, "arena", "create", "arena1")
        op.setLocation(Location(env.world(), 1.0, 64.0, 1.0))
        env.runCommand(op, "arena", "arena1", "spawn", "set", "1")
        op.setLocation(Location(env.world(), 2.0, 64.0, 2.0))
        env.runCommand(op, "arena", "arena1", "spawn", "set", "2")
        op.inventory.setItem(0, env.item(Material.DIAMOND_SWORD))
        env.runCommand(op, "arena", "arena1", "kit", "set")
        val sign = env.signBlock(3, 64, 3)
        op.targetBlock = sign
        env.runCommand(op, "arena", "arena1", "sign", "set")
        env.runCommand(op, "arena", "arena1", "enable")

        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        p1.inventory.setItem(0, env.item(Material.BREAD))
        p2.inventory.setItem(0, env.item(Material.APPLE))

        env.fire(interact(p1, sign))
        assertEquals(ArenaState.Kind.ONEMORE, env.state())
        env.fire(interact(p2, sign))
        assertEquals(ArenaState.Kind.COUNTDOWN, env.state())

        env.tick(6)
        assertEquals(ArenaState.Kind.INGAME, env.state())
        assertEquals(Material.DIAMOND_SWORD, p1.inventory.contents[0]?.type)
        assertEquals(1.0, p1.location.x, 0.001)
        assertEquals(2.0, p2.location.x, 0.001)

        p2.simulateDamage(100.0, attackDamage(p1))
        assertEquals(ArenaState.Kind.WAITING, env.state())
        env.runOneShots()

        assertEquals(Material.BREAD, p1.inventory.contents[0]?.type)
        assertEquals(Material.APPLE, p2.inventory.contents[0]?.type)
        assertEquals(100.0, p1.location.x, 0.001)
        assertEquals(100.0, p2.location.x, 0.001)
        assertNull(env.backupByName("Alice"))
        assertNull(env.backupByName("Bob"))
        assertEquals(1, env.statsRepository.find(p1.uuid)!!.wins)
        assertEquals(1, env.statsRepository.find(p2.uuid)!!.losses)
        env.runCommand(p1, "stats")
        assertTrue(p1.drainMessages().any { it.contains("Win") })
        assertTrue(signLines(sign)[2].contains("Join"))

        env.fire(interact(p1, sign))
        env.fire(interact(p2, sign))
        assertEquals(ArenaState.Kind.COUNTDOWN, env.state())
    }

    @Test
    fun `matches in two arenas progress independently`() {
        env.close()
        env = TestEnv(folder, requiredWins = 2)
        env.newArena("arena1")
        env.newArena("arena2")
        val sign1 = env.signBlock(3, 64, 3)
        val sign2 = env.signBlock(4, 64, 4)
        env.signRepository.setSign(arenaId("arena1"), BlockPosition.new("world", 3, 64, 3))
        env.signRepository.setSign(arenaId("arena2"), BlockPosition.new("world", 4, 64, 4))

        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        val p3 = env.player("Carol")
        val p4 = env.player("Dave")

        env.fire(interact(p1, sign1))
        env.fire(interact(p2, sign1))
        env.fire(interact(p3, sign2))
        env.fire(interact(p4, sign2))

        env.tick(6)
        assertEquals(ArenaState.Kind.INGAME, env.state("arena1"))
        assertEquals(ArenaState.Kind.INGAME, env.state("arena2"))

        fallIntoVoid(p2)
        assertEquals(ArenaState.Kind.ROUNDCOUNTDOWN, env.state("arena1"))
        assertEquals(ArenaState.Kind.INGAME, env.state("arena2"))

        fallIntoVoid(p4)
        assertEquals(ArenaState.Kind.ROUNDCOUNTDOWN, env.state("arena2"))

        env.tick(8)
        assertEquals(ArenaState.Kind.INGAME, env.state("arena1"))
        assertEquals(ArenaState.Kind.INGAME, env.state("arena2"))

        fallIntoVoid(p2)
        assertEquals(ArenaState.Kind.WAITING, env.state("arena1"))
        assertEquals(ArenaState.Kind.INGAME, env.state("arena2"))

        fallIntoVoid(p4)
        assertEquals(ArenaState.Kind.WAITING, env.state("arena2"))

        assertEquals(1, env.statsRepository.find(p1.uuid)!!.wins)
        assertEquals(1, env.statsRepository.find(p3.uuid)!!.wins)
        assertEquals(1, env.statsRepository.find(p2.uuid)!!.losses)
        assertEquals(1, env.statsRepository.find(p4.uuid)!!.losses)
    }

    @Test
    fun `shutdown mid-match restores online players and a restart reloads persisted data`() {
        val arena = env.newArena()
        env.signBlock(3, 64, 3)
        env.signRepository.setSign(arenaId("arena1"), BlockPosition.new("world", 3, 64, 3))
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        p1.inventory.setItem(0, env.item(Material.BREAD))
        p2.inventory.setItem(0, env.item(Material.APPLE))
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)
        assertEquals(ArenaState.Kind.INGAME, env.state())

        env.lifecycle.shutdown()

        assertEquals(Material.BREAD, p1.inventory.contents[0]?.type)
        assertEquals(Material.APPLE, p2.inventory.contents[0]?.type)
        assertNull(env.backupByName("Alice"))
        assertNull(env.backupByName("Bob"))
        assertSame(env.mainBoard, p1.scoreboard)
        assertSame(env.mainBoard, p2.scoreboard)

        env.store.close()
        env.rebuildWith(SqliteStore(folder, Logger.getLogger("1vs1-test")))
        env.lifecycle.load()

        assertTrue(env.sessions.findArena("arena1")!!.enabled)
        assertEquals(arenaId("arena1"), env.signRepository.findSignOwner(BlockPosition.new("world", 3, 64, 3)))

        val sign = env.signBlock(3, 64, 3)
        env.fire(interact(p1, sign))
        env.fire(interact(p2, sign))
        assertEquals(ArenaState.Kind.COUNTDOWN, env.state())
        env.tick(6)
        assertEquals(ArenaState.Kind.INGAME, env.state())
    }

    @Test
    fun `kit items on the cursor or crafting grid are discarded at match end`() {
        val arena = env.newArena()
        env.setKit(arena, PaperInventorySnapshot(items = listOf(env.item(Material.IRON_SWORD))))
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        p1.inventory.setItem(0, env.item(Material.BREAD))
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)
        assertEquals(ArenaState.Kind.INGAME, env.state())
        assertEquals(Material.IRON_SWORD, p1.inventory.contents[0]?.type)

        val grid = env.openCraftingGrid(p1)
        grid.matrix = arrayOf(env.item(Material.IRON_SWORD), null, null, null)
        p1.setItemOnCursor(env.item(Material.IRON_SWORD))

        p2.disconnect()
        env.runOneShots()

        assertEquals(Material.BREAD, p1.inventory.contents[0]?.type)
        assertTrue(p1.itemOnCursor.isEmpty)
        assertTrue(grid.isEmpty())
        assertNull(env.backupByName("Alice"))
    }

    @Test
    fun `items left in the crafting grid during countdown are restored after the match`() {
        val arena = env.newArena()
        env.setKit(arena, PaperInventorySnapshot(items = listOf(env.item(Material.IRON_SWORD))))
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)
        assertEquals(ArenaState.Kind.COUNTDOWN, env.state())

        val grid = env.openCraftingGrid(p1)
        grid.matrix = arrayOf(env.item(Material.APPLE), null, null, null)
        env.tick(6)
        assertEquals(ArenaState.Kind.INGAME, env.state())

        p2.disconnect()
        env.runOneShots()

        assertEquals(Material.APPLE, p1.inventory.contents[0]?.type)
        assertTrue(grid.isEmpty())
    }
}
