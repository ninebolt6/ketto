package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.WorldPosition
import net.ninebolt.onevsone.infrastructure.paper.fixtures.TestEnv
import net.ninebolt.onevsone.infrastructure.paper.fixtures.fallIntoVoid
import net.ninebolt.onevsone.infrastructure.paper.fixtures.twoPlayerIngame
import net.ninebolt.onevsone.infrastructure.paper.fixtures.uuid
import org.bukkit.Location
import org.bukkit.entity.Boat
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerPortalEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.vehicle.VehicleEnterEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArenaListenerTeleportTest {

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

    private fun teleport(player: Player, cause: PlayerTeleportEvent.TeleportCause): PlayerTeleportEvent {
        val w = env.world()
        return PlayerTeleportEvent(player, Location(w, 0.0, 64.0, 0.0), Location(w, 10.0, 64.0, 10.0), cause)
    }

    @Test
    fun `only ender pearl allowed while ingame`() {
        val (p1, _) = env.twoPlayerIngame()

        val pearl = teleport(p1, PlayerTeleportEvent.TeleportCause.ENDER_PEARL)
        env.fire(pearl)
        assertFalse(pearl.isCancelled)

        listOf(
            PlayerTeleportEvent.TeleportCause.COMMAND,
            PlayerTeleportEvent.TeleportCause.PLUGIN,
        ).forEach { cause ->
            val event = teleport(p1, cause)
            env.fire(event)
            assertTrue(event.isCancelled, "cause=$cause")
        }
    }

    @Test
    fun `portal events need own handler`() {
        val (p1, _) = env.twoPlayerIngame()
        val w = env.world()
        val portal = PlayerPortalEvent(
            p1,
            Location(w, 0.0, 64.0, 0.0),
            Location(w, 10.0, 64.0, 10.0),
            PlayerTeleportEvent.TeleportCause.NETHER_PORTAL,
        )
        env.fire(portal)
        assertTrue(portal.isCancelled)
    }

    @Test
    fun `round countdown allows only plugin teleports`() {
        val (p1, p2) = env.twoPlayerIngame()
        fallIntoVoid(p2)
        assertEquals(ArenaState.ROUNDCOUNTDOWN, env.state())

        val pearl = teleport(p1, PlayerTeleportEvent.TeleportCause.ENDER_PEARL)
        env.fire(pearl)
        assertTrue(pearl.isCancelled)

        val plugin = teleport(p1, PlayerTeleportEvent.TeleportCause.PLUGIN)
        env.lookup.scopePluginTeleport(p1.uuid) {
            env.fire(plugin)
        }
        assertFalse(plugin.isCancelled)
    }

    @Test
    fun `marker takes precedence over non plugin cause`() {
        // Plugin teleports can arrive with a cause other than PLUGIN
        val (p1, _) = env.twoPlayerIngame()
        val event = teleport(p1, PlayerTeleportEvent.TeleportCause.COMMAND)
        env.lookup.scopePluginTeleport(p1.uuid) {
            env.fire(event)
        }
        assertFalse(event.isCancelled)
    }

    @Test
    fun `teleports unrestricted while onemore and for outsiders`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        env.join(p1, arena)

        val onemore = teleport(p1, PlayerTeleportEvent.TeleportCause.COMMAND)
        env.fire(onemore)
        assertFalse(onemore.isCancelled)

        val outsider = teleport(env.player("Outsider"), PlayerTeleportEvent.TeleportCause.NETHER_PORTAL)
        env.fire(outsider)
        assertFalse(outsider.isCancelled)
    }

    @Test
    fun `plugin teleports pass marker and external teleports are blocked end to end`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)

        env.tick(6)
        assertEquals(ArenaState.INGAME, env.state())
        assertTrue(p1.hasTeleported())
        assertTrue(p2.hasTeleported())

        p1.clearTeleported()
        p1.teleport(Location(env.world(), 50.0, 64.0, 50.0))
        assertFalse(p1.hasTeleported())

        p1.teleport(
            Location(env.world(), 3.0, 64.0, 3.0),
            PlayerTeleportEvent.TeleportCause.ENDER_PEARL,
        )
        assertTrue(p1.hasTeleported())

        p1.clearTeleported()
        env.playerPort.handle(p1.uuid)!!.teleport(WorldPosition.new("world", 7.0, 64.0, 7.0))
        assertTrue(p1.hasTeleported())
    }

    @Test
    fun `vehicle enter cancelled only while frozen`() {
        val (p1, p2) = env.twoPlayerIngame()
        val boat = env.world().spawnEntity(Location(env.world(), 0.0, 64.0, 0.0), EntityType.OAK_BOAT) as Boat

        val ingame = VehicleEnterEvent(boat, p1)
        env.fire(ingame)
        assertFalse(ingame.isCancelled)

        fallIntoVoid(p2)
        assertEquals(ArenaState.ROUNDCOUNTDOWN, env.state())
        val frozen = VehicleEnterEvent(boat, p1)
        env.fire(frozen)
        assertTrue(frozen.isCancelled)
    }
}
