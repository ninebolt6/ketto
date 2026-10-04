package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.infrastructure.paper.fixtures.TestEnv
import net.ninebolt.onevsone.infrastructure.paper.fixtures.assertFired
import net.ninebolt.onevsone.infrastructure.paper.fixtures.attackDamage
import net.ninebolt.onevsone.infrastructure.paper.fixtures.fallIntoVoid
import net.ninebolt.onevsone.infrastructure.paper.fixtures.genericDamage
import net.ninebolt.onevsone.infrastructure.paper.fixtures.mob
import net.ninebolt.onevsone.infrastructure.paper.fixtures.projectileDamage
import net.ninebolt.onevsone.infrastructure.paper.fixtures.simulation
import net.ninebolt.onevsone.infrastructure.paper.fixtures.spawn
import net.ninebolt.onevsone.infrastructure.paper.fixtures.twoPlayerIngame
import net.ninebolt.onevsone.infrastructure.paper.fixtures.uuid
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Biome
import org.bukkit.damage.DamageType
import org.bukkit.entity.EntityType
import org.bukkit.event.entity.PlayerDeathEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockbukkit.mockbukkit.world.WorldMock
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArenaMatchListenerTest {

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
    fun `death event keeps inventory clears drops and resolves round`() {
        val (p1, p2) = env.twoPlayerIngame()
        p2.simulateDamage(100.0, genericDamage())
        env.assertFired<PlayerDeathEvent> { event ->
            event.keepInventory && event.drops.isEmpty() && event.droppedExp == 0 && event.keepLevel
        }
        assertEquals(ArenaState.Kind.ROUNDCOUNTDOWN, env.state())
        assertEquals(1, env.participation.matchIn("arena1")!!.winsOf(p1.uuid))
    }

    @Test
    fun `non participant death ignored`() {
        val outsider = env.player("Outsider")
        outsider.simulateDamage(100.0, genericDamage())
        env.assertFired<PlayerDeathEvent> { event -> !event.keepInventory && !event.keepLevel }
    }

    @Test
    fun `participant death during countdown respawns and the match still starts`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)
        assertEquals(ArenaState.Kind.COUNTDOWN, env.state())

        p1.simulateDamage(100.0, genericDamage())
        env.assertFired<PlayerDeathEvent> { event -> event.keepInventory && event.keepLevel }
        assertEquals(ArenaState.Kind.COUNTDOWN, env.state())
        assertEquals(arena, env.sessions.arenaIdOf(p1.uuid))

        env.tick(6)
        assertEquals(ArenaState.Kind.INGAME, env.state())
        assertEquals(1, p1.respawnCount)
        assertTrue(p1.hasTeleported())
        assertTrue(p2.hasTeleported())
        assertNull(env.statsRepository.find(p1.uuid))
        assertNull(env.statsRepository.find(p2.uuid))
    }

    @Test
    fun `non player damage ignored`() {
        val event = env.mob().simulateDamage(1.0, genericDamage())
        assertFalse(event.isCancelled)
    }

    @Test
    fun `opponent entity damage allowed in INGAME`() {
        val (p1, p2) = env.twoPlayerIngame()
        val event = p1.simulateDamage(1.0, attackDamage(p2))
        assertFalse(event.isCancelled)
    }

    @Test
    fun `third party and mob damage cancelled in INGAME`() {
        val (p1, _) = env.twoPlayerIngame()
        val outsider = env.player("Outsider")

        val sniped = p1.simulateDamage(1.0, attackDamage(outsider))
        assertTrue(sniped.isCancelled)

        val mobbed = p1.simulateDamage(1.0, attackDamage(env.mob(), DamageType.MOB_ATTACK))
        assertTrue(mobbed.isCancelled)
        assertEquals(20.0, p1.health)
    }

    @Test
    fun `opponent projectile damage attributed via causing entity`() {
        val (p1, p2) = env.twoPlayerIngame()
        val arrow = env.spawn(EntityType.ARROW)
        val allowed = p1.simulateDamage(1.0, projectileDamage(arrow, p2))
        assertFalse(allowed.isCancelled)

        val smuggled = p1.simulateDamage(1.0, projectileDamage(arrow, env.player("Outsider")))
        assertTrue(smuggled.isCancelled)
    }

    @Test
    fun `participant cannot damage outsiders or mobs`() {
        val (p1, _) = env.twoPlayerIngame()
        val outsider = env.player("Outsider")

        val hitPlayer = outsider.simulateDamage(1.0, attackDamage(p1))
        assertTrue(hitPlayer.isCancelled)

        val hitMob = env.mob().simulateDamage(1.0, attackDamage(p1))
        assertTrue(hitMob.isCancelled)
    }

    @Test
    fun `outsiders fighting each other unaffected`() {
        env.twoPlayerIngame()
        val a = env.player("OutsiderA")
        val b = env.player("OutsiderB")
        val event = a.simulateDamage(1.0, attackDamage(b))
        assertFalse(event.isCancelled)
    }

    @Test
    fun `quit of outsider does not touch inventory`() {
        val outsider = env.player("Outsider")
        outsider.inventory.setItem(0, env.item(Material.STONE))
        outsider.disconnect()
        assertEquals(Material.STONE, outsider.inventory.contents[0]?.type)
    }

    @Test
    fun `void fall below zero resolves only when ingame with two players`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        env.join(p1, arena)
        assertEquals(ArenaState.Kind.ONEMORE, env.state())

        val w = env.world()
        val sim = p1.simulation()
        sim.simulatePlayerMove(Location(w, 0.0, -5.0, 0.0))
        assertEquals(ArenaState.Kind.ONEMORE, env.state())

        val p2 = env.player("Bob")
        env.join(p2, arena)
        env.tick(6)

        sim.simulatePlayerMove(Location(w, 0.0, -1.0, 0.0))
        assertEquals(ArenaState.Kind.ROUNDCOUNTDOWN, env.state())
        assertEquals(1, env.participation.matchIn("arena1")!!.winsOf(p2.uuid))
    }

    @Test
    fun `round countdown freezes xz movement but allows y only`() {
        val (p1, p2) = env.twoPlayerIngame()
        fallIntoVoid(p2)

        val sim = p1.simulation()
        val from = p1.location
        val horizontal = sim.simulatePlayerMove(from.clone().add(1.0, 0.0, 0.0))
        // simulatePlayerMove restores the real position only when cancelled, so the event's `to` carries the verdict
        assertEquals(from, horizontal.to)

        val verticalTarget = p1.location.clone().add(0.0, 1.0, 0.0)
        val vertical = sim.simulatePlayerMove(verticalTarget)
        assertEquals(verticalTarget, vertical.to)
    }

    @Test
    fun `void fall uses world min height`() {
        // WorldMock's constructor args are (minHeight, maxHeight, grassHeight)
        val deep = WorldMock(Material.STONE, Biome.PLAINS, -64, 320, 0)
        env.server.addWorld(deep)
        val (p1, p2) = env.twoPlayerIngame()
        val sim = p1.simulation()

        sim.simulatePlayerMove(Location(deep, 0.0, -55.0, 0.0))
        assertEquals(ArenaState.Kind.INGAME, env.state())

        sim.simulatePlayerMove(Location(deep, 0.0, -65.0, 0.0))
        assertEquals(ArenaState.Kind.ROUNDCOUNTDOWN, env.state())
        assertEquals(1, env.participation.matchIn("arena1")!!.winsOf(p2.uuid))
    }

    @Test
    fun `void fall in ROUNDCOUNTDOWN scores again`() {
        val (p1, p2) = env.twoPlayerIngame()
        fallIntoVoid(p2)
        assertEquals(ArenaState.Kind.ROUNDCOUNTDOWN, env.state())

        val sim = p2.simulation()
        val base = p2.location
        sim.simulatePlayerMove(Location(base.world, base.x, -1.0, base.z))
        assertEquals(2, env.participation.matchIn("arena1")!!.winsOf(p1.uuid))
        env.tick(8)
        assertEquals(ArenaState.Kind.INGAME, env.state())

        sim.simulatePlayerMove(Location(base.world, base.x, -1.0, base.z))
        assertEquals(ArenaState.Kind.WAITING, env.state())
    }

    @Test
    fun `z only drift is still reset while movement is frozen`() {
        val (p1, p2) = env.twoPlayerIngame()
        fallIntoVoid(p2)

        val sim = p1.simulation()
        val from = p1.location
        val drifted = sim.simulatePlayerMove(from.clone().add(0.0, 0.0, 1.0))
        assertEquals(from, drifted.to)
    }

    @Test
    fun `move by a non participant is untouched`() {
        env.twoPlayerIngame()
        val outsider = env.player("Carol")

        val target = Location(env.world(), 5.0, 64.0, 5.0)
        val event = outsider.simulation().simulatePlayerMove(target)
        assertEquals(target, event.to)
    }
}
