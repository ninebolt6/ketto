package net.ninebolt.onevsone.infrastructure.paper

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.ninebolt.onevsone.application.JoinOutput
import net.ninebolt.onevsone.domain.BlockPosition
import net.ninebolt.onevsone.domain.fixtures.arenaId
import net.ninebolt.onevsone.infrastructure.paper.fixtures.TestEnv
import net.ninebolt.onevsone.infrastructure.paper.fixtures.breakBlock
import net.ninebolt.onevsone.infrastructure.paper.fixtures.drainMessages
import net.ninebolt.onevsone.infrastructure.paper.fixtures.interact
import net.ninebolt.onevsone.infrastructure.paper.fixtures.nonPlayer
import net.ninebolt.onevsone.infrastructure.paper.fixtures.plainBlock
import net.ninebolt.onevsone.infrastructure.paper.fixtures.signBlock
import net.ninebolt.onevsone.infrastructure.paper.fixtures.simulation
import net.ninebolt.onevsone.infrastructure.paper.fixtures.uuid
import org.bukkit.ExplosionResult
import org.bukkit.block.BlockFace
import org.bukkit.block.Sign
import org.bukkit.block.sign.Side
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArenaSignListenerTest {

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
    fun `registered sign join works and unregistered ignored`() {
        val arena = env.newArena()
        env.signRepo.setSign("arena1", BlockPosition.new("world", 3, 64, 3))
        val p1 = env.player("Alice")

        val unregistered = interact(p1, env.signBlock(9, 64, 9))
        env.fire(unregistered)
        assertNull(env.registry.arenaOf(p1.uuid))

        val registered = interact(p1, env.signBlock(3, 64, 3))
        env.fire(registered)
        assertEquals(arena, env.registry.arenaOf(p1.uuid))
        assertEquals(Event.Result.DENY, registered.useInteractedBlock())
        assertEquals(Event.Result.DENY, registered.useItemInHand())
        assertNotEquals(Event.Result.DENY, unregistered.useInteractedBlock())

        val bob = env.player("Bob")
        val offhand = interact(bob, env.signBlock(3, 64, 3), EquipmentSlot.OFF_HAND)
        env.fire(offhand)
        assertNull(env.registry.arenaOf(bob.uuid))
    }

    @Test
    fun `second player joining by sign starts the match without the wait message`() {
        env.newArena()
        env.signRepo.setSign("arena1", BlockPosition.new("world", 3, 64, 3))
        val block = env.signBlock(3, 64, 3)
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")

        env.fire(interact(p1, block))
        env.fire(interact(p2, block))

        val messages = p2.drainMessages()
        assertTrue(messages.any { it.contains("Joined arena") })
        assertTrue(messages.none { it.contains("one more") })
    }

    @Test
    fun `pending restore join output explains why joining is blocked`() {
        val player = env.player("Alice")

        env.signListener.renderJoin(player, "arena1", JoinOutput.RestorePending)

        val message = player.drainMessages().single()
        assertTrue(message.contains("previous inventory has been restored"))
        assertTrue(message.contains("administrator"))
    }

    @Test
    fun `cannot join sign click shows message`() {
        val arena = env.newArena()
        env.signRepo.setSign("arena1", BlockPosition.new("world", 3, 64, 3))
        val block = env.signBlock(3, 64, 3)
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)

        val p3 = env.player("Carol")
        env.fire(interact(p3, block))
        assertTrue(p3.drainMessages().any { it.contains("This arena is currently in a match") })
    }

    @Test
    fun `sign click on a disabled arena reports not enabled`() {
        env.newArena("arena1", enabled = false)
        env.signRepo.setSign("arena1", BlockPosition.new("world", 3, 64, 3))
        val p1 = env.player("Alice")

        env.fire(interact(p1, env.signBlock(3, 64, 3)))
        assertTrue(p1.drainMessages().any { it.contains("not enabled") })
        assertNull(env.registry.arenaOf(p1.uuid))
    }

    @Test
    fun `sign click while already in the arena reports already joined`() {
        env.newArena()
        env.signRepo.setSign("arena1", BlockPosition.new("world", 3, 64, 3))
        val block = env.signBlock(3, 64, 3)
        val p1 = env.player("Alice")

        env.fire(interact(p1, block))
        p1.drainMessages()
        env.fire(interact(p1, block))
        assertTrue(p1.drainMessages().any { it.contains("already in another arena") })
    }

    @Test
    fun `a sign registered to an invalid arena name reports not found`() {
        env.store.exec(
            "INSERT INTO arenas(name, enabled, spawn1_world, spawn1_x, spawn1_y, spawn1_z, seq) VALUES ('create', 1, 'w', 0, 0, 0, 1)",
        )
        env.signRepo.setSign("create", BlockPosition.new("world", 3, 64, 3))
        val p1 = env.player("Alice")

        env.fire(interact(p1, env.signBlock(3, 64, 3)))
        assertTrue(p1.drainMessages().any { it.contains("does not exist") })
        assertNull(env.registry.arenaOf(p1.uuid))
    }

    @Test
    fun `non sign block and non right click ignored`() {
        env.newArena()
        env.signRepo.setSign("arena1", BlockPosition.new("world", 3, 64, 3))
        val p1 = env.player("Alice")

        env.fire(interact(p1, env.plainBlock(3, 64, 3)))

        val leftClick = interact(p1, env.signBlock(3, 64, 3), action = Action.LEFT_CLICK_BLOCK)
        env.fire(leftClick)
        assertNull(env.registry.arenaOf(p1.uuid))
    }

    @Test
    fun `registered sign cannot be broken until unregistered`() {
        env.newArena()
        env.signRepo.setSign("arena1", BlockPosition.new("world", 3, 64, 3))
        val p1 = env.player("Alice")
        val sim = p1.simulation()

        val registered = sim.breakBlock(env.signBlock(3, 64, 3))
        assertTrue(registered.isCancelled)

        val unregistered = sim.breakBlock(env.signBlock(9, 64, 9))
        assertFalse(unregistered.isCancelled)

        env.signs.clearSign(arenaId("arena1"))
        val freed = sim.breakBlock(env.signBlock(3, 64, 3))
        assertFalse(freed.isCancelled)
    }

    @Test
    fun `registered sign survives explosions`() {
        env.newArena()
        env.signRepo.setSign("arena1", BlockPosition.new("world", 3, 64, 3))
        val sign = env.signBlock(3, 64, 3)
        val plain = env.plainBlock(9, 64, 9)

        val explode = EntityExplodeEvent(
            env.nonPlayer(),
            sign.location,
            mutableListOf(sign, plain),
            0f,
            ExplosionResult.DESTROY,
        )
        env.fire(explode)
        assertEquals(listOf(plain), explode.blockList())

        val blockExplode = BlockExplodeEvent(sign, sign.state, mutableListOf(sign, plain), 0f, ExplosionResult.DESTROY)
        env.fire(blockExplode)
        assertEquals(listOf(plain), blockExplode.blockList())
    }

    @Test
    fun `non sign break is ignored`() {
        env.newArena()
        env.signRepo.setSign("arena1", BlockPosition.new("world", 3, 64, 3))
        val p1 = env.player("Alice")

        val event = p1.simulation().breakBlock(env.plainBlock(3, 64, 3))
        assertFalse(event.isCancelled)
    }

    @Test
    fun `right click without a clicked block is ignored`() {
        env.newArena()
        env.signRepo.setSign("arena1", BlockPosition.new("world", 3, 64, 3))
        val p1 = env.player("Alice")

        val event = PlayerInteractEvent(p1, Action.RIGHT_CLICK_BLOCK, null, null, BlockFace.SELF, EquipmentSlot.HAND)
        env.fire(event)
        assertNull(env.registry.arenaOf(p1.uuid))
        assertTrue(p1.drainMessages().isEmpty())
    }

    @Test
    fun `registered sign renders arena name and state`() {
        env.newArena()
        val block = env.signBlock(3, 64, 3)
        env.signs.setSign(arenaId("arena1"), BlockPosition.new("world", 3, 64, 3))

        val plain = PlainTextComponentSerializer.plainText()
        fun lines() = (0..3).map {
            plain.serialize((block.state as Sign).getSide(Side.FRONT).line(it))
        }

        assertTrue(lines()[1].contains("arena1"))
        assertTrue(lines()[2].contains("Join"))
        assertTrue(lines()[3].contains("Waiting"))

        env.join(env.player("Alice"))
        assertTrue(lines()[3].contains("1 More"))
    }

    @Test
    fun `disabled arena sign shows cannot join and returns to join on enable`() {
        env.newArena("arena1", enabled = false)
        val block = env.signBlock(3, 64, 3)
        env.signs.setSign(arenaId("arena1"), BlockPosition.new("world", 3, 64, 3))

        val plain = PlainTextComponentSerializer.plainText()
        fun lines() = (0..3).map {
            plain.serialize((block.state as Sign).getSide(Side.FRONT).line(it))
        }

        assertTrue(lines()[2].contains("Cannot join"))
        assertTrue(lines()[3].contains("Disabled"))

        env.admin.enable("arena1")
        assertTrue(lines()[2].contains("Join"))
        assertTrue(lines()[3].contains("Waiting"))
    }
}
