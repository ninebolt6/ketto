package net.ninebolt.onevsone.infrastructure.paper

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.ninebolt.onevsone.domain.BlockPosition
import net.ninebolt.onevsone.infrastructure.paper.fixtures.TestEnv
import net.ninebolt.onevsone.infrastructure.paper.fixtures.breakBlock
import net.ninebolt.onevsone.infrastructure.paper.fixtures.drainMessages
import net.ninebolt.onevsone.infrastructure.paper.fixtures.interact
import net.ninebolt.onevsone.infrastructure.paper.fixtures.nonPlayer
import net.ninebolt.onevsone.infrastructure.paper.fixtures.plainBlock
import net.ninebolt.onevsone.infrastructure.paper.fixtures.signBlock
import net.ninebolt.onevsone.infrastructure.paper.fixtures.simulation
import net.ninebolt.onevsone.infrastructure.paper.fixtures.twoPlayerIngame
import net.ninebolt.onevsone.infrastructure.paper.fixtures.uuid
import org.bukkit.ExplosionResult
import org.bukkit.block.Sign
import org.bukkit.block.sign.Side
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.inventory.EquipmentSlot
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.AfterEach
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ArenaListenerSignTest {

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
        assertNull(env.service.arenaIdOf(p1.uuid))

        val registered = interact(p1, env.signBlock(3, 64, 3))
        env.fire(registered)
        assertEquals(arena, env.service.arenaIdOf(p1.uuid))
        assertEquals(Event.Result.DENY, registered.useInteractedBlock())
        assertEquals(Event.Result.DENY, registered.useItemInHand())
        assertNotEquals(Event.Result.DENY, unregistered.useInteractedBlock())

        val bob = env.player("Bob")
        val offhand = interact(bob, env.signBlock(3, 64, 3), EquipmentSlot.OFF_HAND)
        env.fire(offhand)
        assertNull(env.service.arenaIdOf(bob.uuid))
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
        assertTrue(p3.drainMessages().any { it.contains("このアリーナは現在ゲーム中です") })
    }

    @Test
    fun `non sign block and non right click ignored`() {
        env.newArena()
        env.signRepo.setSign("arena1", BlockPosition.new("world", 3, 64, 3))
        val p1 = env.player("Alice")

        env.fire(interact(p1, env.plainBlock(3, 64, 3)))

        val leftClick = interact(p1, env.signBlock(3, 64, 3), action = Action.LEFT_CLICK_BLOCK)
        env.fire(leftClick)
        assertNull(env.service.arenaIdOf(p1.uuid))
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

        env.signs.clearSign("arena1")
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
            env.nonPlayer(), sign.location, mutableListOf(sign, plain), 0f, ExplosionResult.DESTROY
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
    fun `registered sign renders arena name and state`() {
        env.newArena()
        val block = env.signBlock(3, 64, 3)
        env.signs.setSign("arena1", BlockPosition.new("world", 3, 64, 3))

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
    fun `join event triggers pending restore`() {
        val (_, p2) = env.twoPlayerIngame()
        p2.disconnect()

        p2.reconnect()
        assertNull(p2.inventory.contents[0])
    }
}
