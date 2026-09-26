package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.application.port.BackupRef
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.MatchId
import net.ninebolt.onevsone.domain.Participant
import net.ninebolt.onevsone.domain.fixtures.arenaId
import net.ninebolt.onevsone.infrastructure.paper.fixtures.TestEnv
import net.ninebolt.onevsone.infrastructure.paper.fixtures.backupByName
import net.ninebolt.onevsone.infrastructure.paper.fixtures.genericDamage
import net.ninebolt.onevsone.infrastructure.paper.fixtures.uuid
import net.ninebolt.onevsone.infrastructure.paper.fixtures.view
import net.ninebolt.onevsone.infrastructure.persistence.PersistedBackup
import org.bukkit.Material
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

class PaperInventoryRecoveryTest {

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
    fun `final death restores original inventory after respawn`() {
        env.close()
        env = TestEnv(folder, requiredWins = 1)
        val arena = env.newArena()
        env.setKit(arena, PaperInventorySnapshot(items = listOf(env.item(Material.IRON_SWORD))))
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        p1.inventory.setItem(0, env.item(Material.BREAD))
        p2.inventory.setItem(0, env.item(Material.APPLE))
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)
        assertEquals(Material.IRON_SWORD, p2.inventory.contents[0]?.type)

        p2.simulateDamage(100.0, genericDamage())
        assertEquals(ArenaState.Kind.WAITING, env.view().state.kind)
        env.runOneShots()
        assertEquals(1, p2.respawnCount)
        assertNotEquals(Material.APPLE, p2.slotAtRespawn?.type)
        assertEquals(Material.APPLE, p2.inventory.contents[0]?.type)
        assertEquals(1, env.statsRepo.find(p1.uuid)!!.wins)
        assertEquals(1, env.statsRepo.find(p2.uuid)!!.losses)
    }

    @Test
    fun `empty kit does not grant lobby items`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)
        assertEquals(ArenaState.Kind.INGAME, env.view().state.kind)
        assertNull(p1.inventory.contents[0])
    }

    @Test
    fun `pending restore applied on join and discarded`() {
        val participant = Participant.new("Alice")
        val p = env.player("Alice", participant.id)
        p.disconnect()
        p.inventory.setItem(0, env.item(Material.STONE))

        env.arenaRepo.save(Arena.Disabled.new(arenaId("a1")))
        val ref = BackupRef.new(MatchId.new(), participant.id, participant.name)
        env.matchStateRepo.persistMatch(
            ArenaMatch.restored(
                arenaId("a1"),
                requiredWins = 3,
                state = ArenaState.OneMore(participant),
            ),
        )
        env.backupStore.saveBackups(listOf(PersistedBackup(ref, PaperInventorySnapshot())))
        env.matchStateRepo.clearRegistrations()
        env.lifecycle.load()

        p.reconnect()
        assertNull(p.inventory.contents[0])
        assertNull(env.backupByName("Alice"))
    }

    @Test
    fun `final death quit before respawn tick still restores original inventory`() {
        env.close()
        env = TestEnv(folder, requiredWins = 1)
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        p2.inventory.setItem(0, env.item(Material.APPLE))
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)

        p2.simulateDamage(100.0, genericDamage())
        assertEquals(ArenaState.Kind.WAITING, env.view().state.kind)

        p2.disconnect()
        assertEquals(Material.APPLE, p2.inventory.contents[0]?.type)

        p2.reconnect()
        p2.inventory.setItem(0, env.item(Material.GOLDEN_APPLE))
        env.runOneShots()
        assertEquals(Material.GOLDEN_APPLE, p2.inventory.contents[0]?.type)
    }

    @Test
    fun `final death shutdown retains record and rejoin reapplies`() {
        env.close()
        env = TestEnv(folder, requiredWins = 1)
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        p2.inventory.setItem(0, env.item(Material.APPLE))
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)

        p2.simulateDamage(100.0, genericDamage())
        env.lifecycle.shutdown()

        assertEquals(Material.APPLE, p2.inventory.contents[0]?.type)
        assertEquals(p2.uniqueId.toString(), env.backupByName("Bob")!!.playerUuid)

        env.disconnectWithoutQuitHandler(p2)
        p2.reconnect()
        assertEquals(Material.APPLE, p2.inventory.contents[0]?.type)
        assertNull(env.backupByName("Bob"))

        env.runOneShots()
        assertEquals(Material.APPLE, p2.inventory.contents[0]?.type)
    }

    @Test
    fun `snapshot captured at match start reflects countdown window changes`() {
        val arena = env.newArena()
        env.setKit(arena, PaperInventorySnapshot(items = listOf(env.item(Material.IRON_SWORD))))
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        p1.inventory.setItem(0, env.item(Material.BREAD))
        env.join(p1, arena)
        env.join(p2, arena)
        p1.inventory.setItem(0, env.item(Material.APPLE))
        env.tick(5)
        assertNull(env.backupByName("Alice"))
        env.tick()
        assertEquals(ArenaState.Kind.INGAME, env.view().state.kind)
        assertEquals(Material.IRON_SWORD, p1.inventory.contents[0]?.type)
        assertEquals(p1.uniqueId.toString(), env.backupByName("Alice")!!.playerUuid)
        assertEquals(p2.uniqueId.toString(), env.backupByName("Bob")!!.playerUuid)
        env.service.abort(arena)
        assertEquals(Material.APPLE, p1.inventory.contents[0]?.type)
    }
}
