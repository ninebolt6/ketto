package net.ninebolt.onevsone.infrastructure.persistence

import net.ninebolt.onevsone.application.port.BackupRef
import net.ninebolt.onevsone.domain.ArenaDefinition
import net.ninebolt.onevsone.domain.ArenaId
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.MatchId
import net.ninebolt.onevsone.domain.Participant
import net.ninebolt.onevsone.domain.WorldPosition
import net.ninebolt.onevsone.domain.isValidArenaName
import net.ninebolt.onevsone.infrastructure.paper.PaperInventorySnapshot
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.UUID
import java.util.logging.Logger

/** YamlPersistence のシナリオを repository 経由の API で検証する。 */
class YamlPersistenceTest {

    @TempDir
    lateinit var folder: File

    private fun store() = YamlStore(folder, Logger.getLogger("test"))
    private fun arenas() = YamlArenaRepository(store())
    private fun lobby() = YamlLobbyRepository(store())
    private fun signs() = YamlSignRepository(store())
    private fun matchState() = YamlMatchStateRepository(store())
    private fun stats() = YamlPlayerStatsRepository(store())

    @Test
    fun `directories are created`() {
        store()
        assertTrue(File(folder, "arena").isDirectory)
        assertTrue(File(folder, "status").isDirectory)
        assertTrue(File(folder, "stats").isDirectory)
    }

    @Test
    fun `arena round trip keeps fractional yaw pitch and enabled`() {
        val repo = arenas()
        val def = ArenaDefinition(
            ArenaId("a1"),
            enabled = true,
            spawn1 = WorldPosition("world", 1.5, 64.25, -3.75, 12.34f, -56.78f)
        )
        repo.save(def)

        val loaded = arenas().find("a1")!!
        assertTrue(loaded.enabled)
        val spawn1 = loaded.spawn1!!
        assertEquals(1.5, spawn1.x)
        assertEquals(12.34f, spawn1.yaw, 0.001f)
        assertEquals(-56.78f, spawn1.pitch, 0.001f)
    }

    @Test
    fun `missing arena file loads disabled defaults`() {
        val repo = arenas()
        repo.saveArenaNames(listOf("ghost"))
        val arena = repo.find("ghost")!!
        assertFalse(arena.enabled)
        assertNull(arena.spawn1)
    }

    @Test
    fun `stats file uses uuid and records win lose`() {
        val repo = stats()
        val uuid = UUID.randomUUID()
        assertNull(repo.find(uuid))
        repo.recordWin(uuid)
        repo.recordLoss(uuid)
        repo.recordLoss(uuid)
        assertTrue(File(folder, "stats/$uuid.yml").exists())
        val loaded = stats().find(uuid)!!
        assertEquals(1, loaded.wins)
        assertEquals(2, loaded.losses)
    }

    @Test
    fun `status file persists names keyed players and wins`() {
        val id1 = UUID.randomUUID()
        val id2 = UUID.randomUUID()
        val match = ArenaMatch(
            ArenaId("a1"),
            requiredWins = 3,
            state = ArenaState.INGAME,
            participants = listOf(Participant(id1, "Alice"), Participant(id2, "Bob")),
            wins = mapOf(id1 to 2)
        )
        matchState().saveStatus(match)

        val yaml = YamlConfiguration.loadConfiguration(File(folder, "status/a1.yml"))
        assertEquals("INGAME", yaml.getString("status"))
        assertEquals(listOf("Alice", "Bob"), yaml.getStringList("players"))
        assertEquals(2, yaml.getInt("win.Alice"))
    }

    @Test
    fun `participants registered and pending restores survive registration clear`() {
        val uuid = UUID.randomUUID()
        val ref = BackupRef(UUID.randomUUID(), MatchId.newId(), uuid, "Alice")
        val repo = matchState()
        repo.registerParticipant(Participant(uuid, "Alice"), ArenaId("a1"))
        store().saveBackups(listOf(PersistedBackup(ref, PaperInventorySnapshot())))

        var yaml = YamlConfiguration.loadConfiguration(File(folder, "status/players.yml"))
        assertTrue(yaml.getStringList("players").contains("Alice"))
        assertEquals("a1", yaml.getString("arena.Alice"))
        assertEquals(uuid.toString(), yaml.getString("inv.Alice.uuid"))

        repo.clearRegistrations()
        yaml = YamlConfiguration.loadConfiguration(File(folder, "status/players.yml"))
        assertTrue(yaml.getStringList("players").isEmpty())
        assertNull(yaml.getString("arena.Alice"))
        assertNotNull(yaml.getConfigurationSection("inv.Alice"))

        val pending = store().persistedBackups()
        assertEquals(1, pending.size)
        assertEquals(uuid, pending[0].ref.playerId)
        assertEquals("Alice", pending[0].ref.playerName)
    }

    @Test
    fun `unregisterParticipant retains backup and deleteBackup removes it`() {
        val uuid = UUID.randomUUID()
        val ref = BackupRef(UUID.randomUUID(), MatchId.newId(), uuid, "Alice")
        val repo = matchState()
        val s = store()
        repo.registerParticipant(Participant(uuid, "Alice"), ArenaId("a1"))
        s.saveBackups(listOf(PersistedBackup(ref, PaperInventorySnapshot())))

        repo.unregisterParticipant("Alice")
        var yaml = YamlConfiguration.loadConfiguration(File(folder, "status/players.yml"))
        assertTrue(yaml.getStringList("players").isEmpty())
        assertNull(yaml.getString("arena.Alice"))
        assertNotNull(yaml.getConfigurationSection("inv.Alice"))

        s.deleteBackup(ref)
        yaml = YamlConfiguration.loadConfiguration(File(folder, "status/players.yml"))
        assertNull(yaml.getConfigurationSection("inv.Alice"))
    }

    @Test
    fun `deleteBackup ignores mismatched backup id`() {
        val uuid = UUID.randomUUID()
        val ref = BackupRef(UUID.randomUUID(), MatchId.newId(), uuid, "Alice")
        val s = store()
        s.saveBackups(listOf(PersistedBackup(ref, PaperInventorySnapshot())))

        s.deleteBackup(BackupRef(UUID.randomUUID(), MatchId.newId(), uuid, "Alice"))
        assertNotNull(
            YamlConfiguration.loadConfiguration(File(folder, "status/players.yml"))
                .getConfigurationSection("inv.Alice")
        )

        s.deleteBackup(ref)
        assertNull(
            YamlConfiguration.loadConfiguration(File(folder, "status/players.yml"))
                .getConfigurationSection("inv.Alice")
        )
    }

    @Test
    fun `malformed players yaml throws and file stays byte identical`() {
        val file = File(folder, "status/players.yml")
        File(folder, "status").mkdirs()
        file.writeText("players: [unclosed")
        val before = file.readBytes()
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
            matchState().unregisterParticipant("Alice")
        }
        org.junit.jupiter.api.Assertions.assertArrayEquals(before, file.readBytes())
    }

    @Test
    fun `registerParticipant writes membership only`() {
        val uuid = UUID.randomUUID()
        matchState().registerParticipant(Participant(uuid, "Alice"), ArenaId("a1"))
        val yaml = YamlConfiguration.loadConfiguration(File(folder, "status/players.yml"))
        assertTrue(yaml.getStringList("players").contains("Alice"))
        assertEquals("a1", yaml.getString("arena.Alice"))
        assertNull(yaml.getConfigurationSection("inv.Alice"))
    }

    @Test
    fun `saveBackups persists both snapshots in one file`() {
        val s = store()
        val u1 = UUID.randomUUID()
        val u2 = UUID.randomUUID()
        val repo = matchState()
        repo.registerParticipant(Participant(u1, "Alice"), ArenaId("a1"))
        repo.registerParticipant(Participant(u2, "Bob"), ArenaId("a1"))
        val match = MatchId.newId()
        s.saveBackups(
            listOf(
                PersistedBackup(BackupRef(UUID.randomUUID(), match, u1, "Alice"), PaperInventorySnapshot()),
                PersistedBackup(BackupRef(UUID.randomUUID(), match, u2, "Bob"), PaperInventorySnapshot())
            )
        )
        val yaml = YamlConfiguration.loadConfiguration(File(folder, "status/players.yml"))
        assertEquals(u1.toString(), yaml.getString("inv.Alice.uuid"))
        assertEquals(u2.toString(), yaml.getString("inv.Bob.uuid"))
        assertNotNull(yaml.getConfigurationSection("inv.Alice"))
        assertNotNull(yaml.getConfigurationSection("inv.Bob"))
    }

    @Test
    fun `malformed stats yaml throws`() {
        val uuid = UUID.randomUUID()
        File(folder, "stats").mkdirs()
        File(folder, "stats/$uuid.yml").writeText("win: [broken")
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
            stats().find(uuid)
        }
    }

    @Test
    fun `legacy pending restore without uuid is loaded`() {
        File(folder, "status").mkdirs()
        val file = File(folder, "status/players.yml")
        val yaml = YamlConfiguration()
        yaml.set("inv.Legacy.armor", emptyList<Any>())
        yaml.set("inv.Legacy.item", emptyList<Any>())
        yaml.save(file)
        val pending = store().persistedBackups()
        assertEquals(1, pending.size)
        assertEquals("Legacy", pending[0].ref.playerName)
        assertNull(pending[0].ref.playerId)
    }

    @Test
    fun `lobby and sign locations persist`() {
        lobby().setLobby(WorldPosition("lobby", 1.0, 2.0, 3.0, 45.5f, 10.25f))
        signs().setSign("a1", WorldPosition("world", 5.0, 64.0, 5.0))
        val lobby = lobby().lobby()!!
        assertEquals("lobby", lobby.world)
        assertEquals(45.5f, lobby.yaw, 0.001f)
        val sign = signs().signLocation("a1")!!
        assertEquals(5.0, sign.x)
        assertEquals("a1", signs().signOwner("world", 5.0, 64.0, 5.0))
        signs().clearSign("a1")
        assertNull(signs().signLocation("a1"))
    }

    @Test
    fun `invalid arena names rejected`() {
        for (bad in listOf("", "a/b", "a\\b", "a.b", "..", "players", "PLAYERS", "Players", "a b", "ab", "x".repeat(65))) {
            assertFalse(isValidArenaName(bad), "expected '$bad' rejected")
        }
        assertTrue(isValidArenaName("arena-1_2"))
    }

    @Test
    fun `deleteArena removes arena and status files`() {
        val repo = arenas()
        repo.save(ArenaDefinition(ArenaId("a1")))
        matchState().saveStatus(ArenaMatch(ArenaId("a1"), requiredWins = 3))
        assertTrue(File(folder, "arena/a1.yml").exists())
        repo.delete("a1")
        assertFalse(File(folder, "arena/a1.yml").exists())
        assertFalse(File(folder, "status/a1.yml").exists())
    }

    @Test
    fun `saveArena keeps inventory section written by equipment adapter`() {
        val repo = arenas()
        val s = store()
        val def = ArenaDefinition(ArenaId("a1"), enabled = true)
        repo.save(def)
        s.saveArenaKit("a1", PaperInventorySnapshot(items = listOf(null)))
        repo.save(ArenaDefinition(ArenaId("a1"), enabled = false))
        val yaml = YamlConfiguration.loadConfiguration(File(folder, "arena/a1.yml"))
        assertFalse(yaml.getBoolean("enabled"))
        assertNotNull(yaml.getList("inventory.item"))
    }
}
