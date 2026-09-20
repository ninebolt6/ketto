package net.ninebolt.onevsone.infrastructure.persistence

import net.ninebolt.onevsone.application.port.BackupRef
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.MatchId
import net.ninebolt.onevsone.domain.Participant
import net.ninebolt.onevsone.domain.WorldPosition
import net.ninebolt.onevsone.infrastructure.paper.PaperInventorySnapshot
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.logging.Logger
import kotlin.uuid.Uuid

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
    private fun backups() = YamlBackupStore(store())
    private fun kits() = YamlKitStore(store())

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
        val def = Arena.new(
            Arena.Id.new("a1"),
            enabled = true,
            spawn1 = WorldPosition.new("world", 1.5, 64.25, -3.75, 12.34f, -56.78f)
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
        val arena = arenas().find("ghost")!!
        assertFalse(arena.enabled)
        assertNull(arena.spawn1)
    }

    @Test
    fun `loadAll follows index order and skips duplicates`() {
        val repo = arenas()
        repo.save(Arena.new(Arena.Id.new("b1")))
        repo.save(Arena.new(Arena.Id.new("a1"), enabled = true))
        repo.save(Arena.new(Arena.Id.new("b1"), enabled = true))

        val loaded = arenas().loadAll()
        assertEquals(listOf("b1", "a1"), loaded.map { it.name })
        assertTrue(loaded[1].enabled)

        repo.delete("b1")
        assertEquals(listOf("a1"), arenas().loadAll().map { it.name })
        assertFalse(File(folder, "arena/b1.yml").exists())
    }

    @Test
    fun `loadAll skips invalid and duplicate index entries`() {
        File(folder, "arenalist.yml").writeText("arenas: [a1, 'a/b', A1]")
        arenas().save(Arena.new(Arena.Id.new("a1")))
        assertEquals(listOf("a1"), arenas().loadAll().map { it.name })
    }

    @Test
    fun `stats file uses uuid and records win lose`() {
        val repo = stats()
        val uuid = Uuid.random()
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
        val p1 = Participant.new("Alice")
        val p2 = Participant.new("Bob")
        val match = ArenaMatch.restored(
            Arena.Id.new("a1"),
            requiredWins = 3,
            state = ArenaState.INGAME,
            participants = listOf(p1, p2),
            wins = mapOf(p1.id to 2)
        )
        matchState().saveStatus(match)

        val yaml = YamlConfiguration.loadConfiguration(File(folder, "status/a1.yml"))
        assertEquals("INGAME", yaml.getString("status"))
        assertEquals(listOf("Alice", "Bob"), yaml.getStringList("players"))
        assertEquals(2, yaml.getInt("win.Alice"))
    }

    @Test
    fun `participants registered and pending restores survive registration clear`() {
        val p = Participant.new("Alice")
        val ref = BackupRef.new(MatchId.new(), p.id, p.name)
        val repo = matchState()
        repo.registerParticipant(p, Arena.Id.new("a1"))
        backups().saveBackups(listOf(PersistedBackup(ref, PaperInventorySnapshot())))

        var yaml = YamlConfiguration.loadConfiguration(File(folder, "status/players.yml"))
        assertTrue(yaml.getStringList("players").contains("Alice"))
        assertEquals("a1", yaml.getString("arena.Alice"))
        assertEquals(p.id.toString(), yaml.getString("inv.Alice.uuid"))

        repo.clearRegistrations()
        yaml = YamlConfiguration.loadConfiguration(File(folder, "status/players.yml"))
        assertTrue(yaml.getStringList("players").isEmpty())
        assertNull(yaml.getString("arena.Alice"))
        assertNotNull(yaml.getConfigurationSection("inv.Alice"))

        val pending = backups().persistedBackups()
        assertEquals(1, pending.size)
        assertEquals(p.id, pending[0].ref.playerId)
        assertEquals("Alice", pending[0].ref.playerName)
    }

    @Test
    fun `unregisterParticipant retains backup and deleteBackup removes it`() {
        val p = Participant.new("Alice")
        val ref = BackupRef.new(MatchId.new(), p.id, p.name)
        val repo = matchState()
        val s = backups()
        repo.registerParticipant(p, Arena.Id.new("a1"))
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
        val p = Participant.new("Alice")
        val ref = BackupRef.new(MatchId.new(), p.id, p.name)
        val s = backups()
        s.saveBackups(listOf(PersistedBackup(ref, PaperInventorySnapshot())))

        s.deleteBackup(BackupRef.new(MatchId.new(), p.id, p.name))
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
    fun `saveBackups evacuates a foreign record with the same name`() {
        val s = backups()
        val original = Participant.new("Alice")
        val old = BackupRef.new(MatchId.new(), original.id, original.name)
        s.saveBackups(listOf(PersistedBackup(old, PaperInventorySnapshot(items = listOf(null)))))

        // 同名の別人が参加してバックアップを保存する
        val other = Participant.new("Alice")
        val fresh = BackupRef.new(MatchId.new(), other.id, other.name)
        s.saveBackups(listOf(PersistedBackup(fresh, PaperInventorySnapshot())))

        val yaml = YamlConfiguration.loadConfiguration(File(folder, "status/players.yml"))
        assertEquals(other.id.toString(), yaml.getString("inv.Alice.uuid"))
        val moved = "inv.Alice__${old.backupId}"
        assertEquals(original.id.toString(), yaml.getString("$moved.uuid"))
        assertEquals("Alice", yaml.getString("$moved.name"))

        // 両方が読み出せ、旧レコードは id 一致で削除できる
        assertEquals(2, backups().persistedBackups().size)
        backups().deleteBackup(old)
        val after = YamlConfiguration.loadConfiguration(File(folder, "status/players.yml"))
        assertNull(after.getConfigurationSection(moved))
        assertEquals(other.id.toString(), after.getString("inv.Alice.uuid"))
    }

    @Test
    fun `saveBackups overwrites own record without evacuating`() {
        val s = backups()
        val p = Participant.new("Alice")
        s.saveBackups(listOf(PersistedBackup(BackupRef.new(MatchId.new(), p.id, p.name), PaperInventorySnapshot())))
        val second = BackupRef.new(MatchId.new(), p.id, p.name)
        s.saveBackups(listOf(PersistedBackup(second, PaperInventorySnapshot(items = listOf(null)))))

        val yaml = YamlConfiguration.loadConfiguration(File(folder, "status/players.yml"))
        assertEquals(second.backupId.toString(), yaml.getString("inv.Alice.id"))
        assertEquals(1, backups().persistedBackups().size)
    }

    @Test
    fun `failed save leaves no temp file`() {
        val s = store()
        // 一時ファイルのパスをディレクトリで塞ぎ、書き出しを失敗させる
        val tmp = File(folder, "status/players.yml.tmp")
        tmp.mkdirs()
        assertThrows(IllegalStateException::class.java) {
            s.save(YamlConfiguration(), File(folder, "status/players.yml"))
        }
        assertFalse(tmp.exists())
    }

    @Test
    fun `malformed players yaml throws and file stays byte identical`() {
        val file = File(folder, "status/players.yml")
        File(folder, "status").mkdirs()
        file.writeText("players: [unclosed")
        val before = file.readBytes()
        assertThrows(IllegalStateException::class.java) {
            matchState().unregisterParticipant("Alice")
        }
        assertArrayEquals(before, file.readBytes())
    }

    @Test
    fun `registerParticipant writes membership only`() {
        matchState().registerParticipant(Participant.new("Alice"), Arena.Id.new("a1"))
        val yaml = YamlConfiguration.loadConfiguration(File(folder, "status/players.yml"))
        assertTrue(yaml.getStringList("players").contains("Alice"))
        assertEquals("a1", yaml.getString("arena.Alice"))
        assertNull(yaml.getConfigurationSection("inv.Alice"))
    }

    @Test
    fun `saveBackups persists both snapshots in one file`() {
        val s = backups()
        val p1 = Participant.new("Alice")
        val p2 = Participant.new("Bob")
        val repo = matchState()
        repo.registerParticipant(p1, Arena.Id.new("a1"))
        repo.registerParticipant(p2, Arena.Id.new("a1"))
        val match = MatchId.new()
        s.saveBackups(
            listOf(
                PersistedBackup(BackupRef.new(match, p1.id, p1.name), PaperInventorySnapshot()),
                PersistedBackup(BackupRef.new(match, p2.id, p2.name), PaperInventorySnapshot())
            )
        )
        val yaml = YamlConfiguration.loadConfiguration(File(folder, "status/players.yml"))
        assertEquals(p1.id.toString(), yaml.getString("inv.Alice.uuid"))
        assertEquals(p2.id.toString(), yaml.getString("inv.Bob.uuid"))
        assertNotNull(yaml.getConfigurationSection("inv.Alice"))
        assertNotNull(yaml.getConfigurationSection("inv.Bob"))
    }

    @Test
    fun `malformed stats yaml throws`() {
        val uuid = Uuid.random()
        File(folder, "stats").mkdirs()
        File(folder, "stats/$uuid.yml").writeText("win: [broken")
        assertThrows(IllegalStateException::class.java) {
            stats().find(uuid)
        }
    }

    @Test
    fun `blank spawn world is rejected as corrupt`() {
        store()
        File(folder, "arena/a1.yml").writeText("enabled: true\nspawn1:\n  world: ''\n  x: 0.0\n  y: 64.0\n  z: 0.0\n")
        assertThrows(IllegalStateException::class.java) {
            arenas().find("a1")
        }
    }

    @Test
    fun `negative stats yaml throws`() {
        val uuid = Uuid.random()
        File(folder, "stats").mkdirs()
        File(folder, "stats/$uuid.yml").writeText("win: -1\nlose: 0\n")
        assertThrows(IllegalStateException::class.java) {
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
        val pending = backups().persistedBackups()
        assertEquals(1, pending.size)
        assertEquals("Legacy", pending[0].ref.playerName)
        assertNull(pending[0].ref.playerId)
        // 識別子が無い旧レコードは採番して書き戻す(以後は id で同一性判定できる)
        assertEquals(
            pending[0].ref.backupId.toString(),
            YamlConfiguration.loadConfiguration(file).getString("inv.Legacy.id")
        )
    }

    @Test
    fun `lobby and sign locations persist`() {
        lobby().setLobby(WorldPosition.new("lobby", 1.0, 2.0, 3.0, 45.5f, 10.25f))
        signs().setSign("a1", WorldPosition.new("world", 5.0, 64.0, 5.0))
        assertTrue(File(folder, "lobby.yml").exists())
        assertNotNull(
            YamlConfiguration.loadConfiguration(File(folder, "arena/a1.yml"))
                .getConfigurationSection("sign")
        )
        val lobby = lobby().lobby()!!
        assertEquals("lobby", lobby.world)
        assertEquals(45.5f, lobby.yaw, 0.001f)
        val sign = signs().signLocation("a1")!!
        assertEquals(5.0, sign.x)
        assertEquals("a1", signs().signOwner("world", 5, 64, 5))
        signs().clearSign("a1")
        assertNull(signs().signLocation("a1"))
        assertNull(signs().signOwner("world", 5, 64, 5))
    }

    @Test
    fun `repositories never write config yml`() {
        val file = File(folder, "config.yml")
        val yaml = YamlConfiguration()
        yaml.set("prefix", "&9[X] ")
        yaml.save(file)
        val before = file.readBytes()

        lobby().setLobby(WorldPosition.new("lobby", 1.0, 2.0, 3.0))
        signs().setSign("a1", WorldPosition.new("world", 5.0, 64.0, 5.0))
        assertArrayEquals(before, file.readBytes())
    }

    @Test
    fun `sign index is rebuilt from arena files by a new instance`() {
        signs().setSign("a1", WorldPosition.new("world", 5.0, 64.0, 5.0))
        // 別インスタンスは index を持たないので arena/<name>.yml から構築する
        assertEquals("a1", signs().signOwner("world", 5, 64, 5))
        assertEquals(5.0, signs().signLocation("a1")!!.x)
    }

    @Test
    fun `setSign releases old position when re-registered`() {
        val repo = signs()
        repo.setSign("a1", WorldPosition.new("world", 5.0, 64.0, 5.0))
        repo.setSign("a1", WorldPosition.new("world", 9.0, 64.0, 9.0))
        assertNull(repo.signOwner("world", 5, 64, 5))
        assertEquals("a1", repo.signOwner("world", 9, 64, 9))
        assertEquals(9.0, repo.signLocation("a1")!!.x)
    }

    @Test
    fun `clearSign does not recreate a deleted arena file`() {
        val repo = signs()
        repo.setSign("a1", WorldPosition.new("world", 5.0, 64.0, 5.0))
        arenas().delete("a1")
        repo.clearSign("a1")
        assertFalse(File(folder, "arena/a1.yml").exists())
        assertNull(repo.signOwner("world", 5, 64, 5))
    }

    @Test
    fun `sign section survives arena save`() {
        signs().setSign("a1", WorldPosition.new("world", 5.0, 64.0, 5.0))
        arenas().save(Arena.new(Arena.Id.new("a1"), enabled = true))
        val yaml = YamlConfiguration.loadConfiguration(File(folder, "arena/a1.yml"))
        assertNotNull(yaml.getConfigurationSection("sign"))
        assertEquals("a1", signs().signOwner("world", 5, 64, 5))
    }

    @Test
    fun `invalid arena names rejected`() {
        for (bad in listOf("", "a/b", "a\\b", "a.b", "..", "players", "PLAYERS", "Players", "a b", "ab", "x".repeat(65), "a:b", " ab", "ab ")) {
            assertNull(Arena.Id.of(bad))
            assertThrows(IllegalArgumentException::class.java) { Arena.Id.new(bad) }
        }
        assertNotNull(Arena.Id.of("arena-1_2"))
    }

    @Test
    fun `deleteArena removes arena and status files`() {
        val repo = arenas()
        repo.save(Arena.new(Arena.Id.new("a1")))
        matchState().saveStatus(ArenaMatch.new(Arena.Id.new("a1"), requiredWins = 3))
        assertTrue(File(folder, "arena/a1.yml").exists())
        repo.delete("a1")
        assertFalse(File(folder, "arena/a1.yml").exists())
        assertFalse(File(folder, "status/a1.yml").exists())
    }

    @Test
    fun `saveArena keeps inventory section written by equipment adapter`() {
        val repo = arenas()
        val s = kits()
        val def = Arena.new(Arena.Id.new("a1"), enabled = true)
        repo.save(def)
        s.saveArenaKit("a1", PaperInventorySnapshot(items = listOf(null)))
        repo.save(Arena.new(Arena.Id.new("a1"), enabled = false))
        val yaml = YamlConfiguration.loadConfiguration(File(folder, "arena/a1.yml"))
        assertFalse(yaml.getBoolean("enabled"))
        assertNotNull(yaml.getList("inventory.item"))
    }
}
