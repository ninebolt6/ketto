package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.application.port.BackupRef
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.DefeatCause
import net.ninebolt.onevsone.domain.MatchId
import net.ninebolt.onevsone.domain.Participant
import net.ninebolt.onevsone.infrastructure.paper.fixtures.TestEnv
import net.ninebolt.onevsone.infrastructure.paper.fixtures.playersYaml
import net.ninebolt.onevsone.infrastructure.paper.fixtures.uuid
import net.ninebolt.onevsone.infrastructure.paper.fixtures.view
import net.ninebolt.onevsone.infrastructure.persistence.PersistedBackup
import org.bukkit.Material
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.uuid.Uuid

/** インベントリバックアップの取得・復元・保留のシナリオ。 */
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
    fun `abort during countdown stops task and leaves waiting inventories untouched`() {
        val arena = env.newArena()
        env.setKit(arena, PaperInventorySnapshot(items = listOf(env.item(Material.IRON_SWORD))))
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(2)
        env.service.abort(arena)
        assertEquals(ArenaState.WAITING, env.view().state)
        assertTrue(env.view().participants.isEmpty())
        env.tick(6)
        assertFalse(p1.hasTeleported())
        assertFalse(p2.hasTeleported())
        assertNull(p1.inventory.contents[0])
    }

    @Test
    fun `abort during pending respawn never reapplies kit`() {
        val arena = env.newArena()
        env.setKit(arena, PaperInventorySnapshot(items = listOf(env.item(Material.IRON_SWORD))))
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)

        p2.health = 0.0
        env.service.defeat(p2.uuid, DefeatCause.DEATH)
        env.service.abort(arena)
        env.runOneShots()
        assertNull(p2.inventory.contents[0])
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

        p2.health = 0.0
        env.service.defeat(p2.uuid, DefeatCause.DEATH)
        assertEquals(ArenaState.WAITING, env.view().state)
        env.runOneShots()
        assertEquals(1, p2.respawnCount)
        assertEquals(Material.APPLE, p2.inventory.contents[0]?.type)
        assertEquals(1, env.statsRepo.find(p1.uuid)!!.wins)
        assertEquals(1, env.statsRepo.find(p2.uuid)!!.losses)
    }

    @Test
    fun `empty snapshot restores to empty inventory`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)
        env.removePlayer(p1)
        env.quit(p1)
        assertNull(p1.inventory.contents[0])
    }

    @Test
    fun `empty kit does not grant lobby items`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)
        assertNull(p1.inventory.contents[0])
    }

    @Test
    fun `pending restore applied on join and discarded`() {
        val participant = Participant.new("Alice")
        val ref = BackupRef.new(MatchId.newId(), participant.id, participant.name)
        env.matchStateRepo.registerParticipant(participant, Arena.Id.new("a1"))
        env.backupStore.saveBackups(listOf(PersistedBackup(ref, PaperInventorySnapshot())))
        env.matchStateRepo.clearRegistrations()
        env.service.load()

        val p = env.player("Alice", participant.id)
        p.inventory.setItem(0, env.item(Material.STONE))
        env.service.restorePending(p.uuid, p.name)
        assertNull(p.inventory.contents[0])
        assertNull(env.playersYaml().getConfigurationSection("inv.Alice"))
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

        p2.health = 0.0
        env.service.defeat(p2.uuid, DefeatCause.DEATH)
        assertEquals(ArenaState.WAITING, env.view().state)

        env.removePlayer(p2)
        env.quit(p2)
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

        p2.health = 0.0
        env.service.defeat(p2.uuid, DefeatCause.DEATH)
        env.service.shutdown()

        assertEquals(Material.APPLE, p2.inventory.contents[0]?.type)
        assertEquals(p2.uniqueId.toString(), env.playersYaml().getString("inv.Bob.uuid"))

        env.service.restorePending(p2.uuid, p2.name)
        assertEquals(Material.APPLE, p2.inventory.contents[0]?.type)
        assertNull(env.playersYaml().getConfigurationSection("inv.Bob"))

        env.runOneShots()
        assertEquals(Material.APPLE, p2.inventory.contents[0]?.type)
    }

    @Test
    fun `abort dead player restores on next tick and stale callback cannot reapply kit`() {
        val arena = env.newArena()
        env.setKit(arena, PaperInventorySnapshot(items = listOf(env.item(Material.IRON_SWORD))))
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        p2.inventory.setItem(0, env.item(Material.APPLE))
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)

        p2.health = 0.0
        env.service.defeat(p2.uuid, DefeatCause.DEATH)
        env.service.abort(arena)
        env.runOneShots()
        assertEquals(Material.APPLE, p2.inventory.contents[0]?.type)

        env.join(p2, arena)
        assertEquals(arena, env.service.arenaIdOf(p2.uuid))
        assertEquals(Material.APPLE, p2.inventory.contents[0]?.type)
    }

    @Test
    fun `abort retains pending restore for offline participant`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        p2.inventory.setItem(0, env.item(Material.APPLE))
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)

        env.removePlayer(p2)
        env.service.abort(arena)

        assertEquals(p2.uniqueId.toString(), env.playersYaml().getString("inv.Bob.uuid"))

        p2.reconnect()
        env.service.restorePending(p2.uuid, p2.name)
        assertEquals(Material.APPLE, p2.inventory.contents[0]?.type)
        assertNull(env.playersYaml().getConfigurationSection("inv.Bob"))
    }

    @Test
    fun `name collision does not restore and renamed uuid does`() {
        val arena = env.newArena()
        val uuid = Uuid.random()
        val original = env.player("Alice", uuid)
        val bob = env.player("Bob")
        original.inventory.setItem(0, env.item(Material.DIAMOND))
        env.join(original, arena)
        env.join(bob, arena)
        env.tick(6)
        env.removePlayer(original)
        env.service.abort(arena)

        val squatter = env.player("Alice")
        squatter.inventory.setItem(0, env.item(Material.STONE))
        env.service.restorePending(squatter.uuid, squatter.name)
        assertEquals(Material.STONE, squatter.inventory.contents[0]?.type)

        val renamed = env.player("Alice2", uuid)
        env.service.restorePending(renamed.uuid, renamed.name)
        assertEquals(Material.DIAMOND, renamed.inventory.contents[0]?.type)
        assertNull(env.playersYaml().getConfigurationSection("inv.Alice"))
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
        assertNull(env.playersYaml().getConfigurationSection("inv.Alice"))
        env.tick()
        assertEquals(ArenaState.INGAME, env.view().state)
        assertEquals(Material.IRON_SWORD, p1.inventory.contents[0]?.type)
        val yaml = env.playersYaml()
        assertEquals(p1.uniqueId.toString(), yaml.getString("inv.Alice.uuid"))
        assertEquals(p2.uniqueId.toString(), yaml.getString("inv.Bob.uuid"))
        env.service.abort(arena)
        assertEquals(Material.APPLE, p1.inventory.contents[0]?.type)
    }
}
