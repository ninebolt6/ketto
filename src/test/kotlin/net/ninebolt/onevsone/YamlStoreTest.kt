package net.ninebolt.onevsone

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

class YamlStoreTest {

    @TempDir
    lateinit var folder: File

    private fun store() = YamlStore(folder, Logger.getLogger("test"))

    @Test
    fun `directories are created`() {
        store()
        assertTrue(File(folder, "arena").isDirectory)
        assertTrue(File(folder, "status").isDirectory)
        assertTrue(File(folder, "stats").isDirectory)
    }

    @Test
    fun `arena round trip keeps fractional yaw pitch and enabled`() {
        val s = store()
        val arena = Arena("a1", enabled = true)
        arena.spawn1 = SavedLocation("world", 1.5, 64.25, -3.75, 12.34f, -56.78f)
        s.saveArena(arena)

        val loaded = store().loadArena("a1")!!
        assertTrue(loaded.enabled)
        assertEquals(1.5, loaded.spawn1!!.x)
        assertEquals(12.34f, loaded.spawn1!!.yaw, 0.001f)
        assertEquals(-56.78f, loaded.spawn1!!.pitch, 0.001f)
    }

    @Test
    fun `missing arena file loads disabled defaults`() {
        val s = store()
        s.saveArenaNames(listOf("ghost"))
        val arena = s.loadArena("ghost")!!
        assertFalse(arena.enabled)
        assertNull(arena.spawn1)
    }

    @Test
    fun `stats file uses uuid and records win lose`() {
        val s = store()
        val uuid = UUID.randomUUID()
        assertFalse(s.statsExist(uuid))
        s.addWin(uuid)
        s.addLose(uuid)
        s.addLose(uuid)
        assertTrue(File(folder, "stats/$uuid.yml").exists())
        val (win, lose) = store().readStats(uuid)
        assertEquals(1, win)
        assertEquals(2, lose)
    }

    @Test
    fun `status file persists names keyed players and wins`() {
        val s = store()
        val arena = Arena("a1")
        val id1 = UUID.randomUUID()
        val id2 = UUID.randomUUID()
        arena.state = ArenaState.INGAME
        arena.players += Participant(id1, "Alice", InventorySnapshot())
        arena.players += Participant(id2, "Bob", InventorySnapshot())
        arena.wins[id1] = 2
        s.saveStatus(arena)

        val yaml = YamlConfiguration.loadConfiguration(File(folder, "status/a1.yml"))
        assertEquals("INGAME", yaml.getString("status"))
        assertEquals(listOf("Alice", "Bob"), yaml.getStringList("players"))
        assertEquals(2, yaml.getInt("win.Alice"))
    }

    @Test
    fun `participants registered and pending restores survive registration clear`() {
        val s = store()
        val uuid = UUID.randomUUID()
        val snapshot = InventorySnapshot()
        s.registerParticipant(Participant(uuid, "Alice", snapshot), "a1")

        var yaml = YamlConfiguration.loadConfiguration(File(folder, "status/players.yml"))
        assertTrue(yaml.getStringList("players").contains("Alice"))
        assertEquals("a1", yaml.getString("arena.Alice"))
        assertEquals(uuid.toString(), yaml.getString("inv.Alice.uuid"))

        s.clearRegistrations()
        yaml = YamlConfiguration.loadConfiguration(File(folder, "status/players.yml"))
        assertTrue(yaml.getStringList("players").isEmpty())
        assertNull(yaml.getString("arena.Alice"))
        assertNotNull(yaml.getConfigurationSection("inv.Alice"))

        val pending = store().pendingRestores()
        assertEquals(1, pending.size)
        assertEquals(uuid, pending[0].uuid)
        assertEquals("Alice", pending[0].name)

        store().unregisterParticipant("Alice")
        yaml = YamlConfiguration.loadConfiguration(File(folder, "status/players.yml"))
        assertNull(yaml.getConfigurationSection("inv.Alice"))
    }

    @Test
    fun `unregisterParticipant can retain snapshot for pending restore`() {
        val s = store()
        val uuid = UUID.randomUUID()
        s.registerParticipant(Participant(uuid, "Alice", InventorySnapshot()), "a1")

        s.unregisterParticipant("Alice", discardSnapshot = false)
        var yaml = YamlConfiguration.loadConfiguration(File(folder, "status/players.yml"))
        assertTrue(yaml.getStringList("players").isEmpty())
        assertNull(yaml.getString("arena.Alice"))
        assertNotNull(yaml.getConfigurationSection("inv.Alice"))

        s.discardPendingRestore("Alice")
        yaml = YamlConfiguration.loadConfiguration(File(folder, "status/players.yml"))
        assertNull(yaml.getConfigurationSection("inv.Alice"))
    }

    @Test
    fun `malformed players yaml throws and file stays byte identical`() {
        val s = store()
        val file = File(folder, "status/players.yml")
        file.writeText("players: [unclosed")
        val before = file.readBytes()
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
            s.unregisterParticipant("Alice")
        }
        org.junit.jupiter.api.Assertions.assertArrayEquals(before, file.readBytes())
    }

    @Test
    fun `malformed stats yaml throws`() {
        val s = store()
        val uuid = UUID.randomUUID()
        File(folder, "stats/$uuid.yml").writeText("win: [broken")
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
            s.readStats(uuid)
        }
    }

    @Test
    fun `legacy pending restore without uuid is loaded`() {
        val file = File(folder, "status/players.yml")
        val yaml = YamlConfiguration()
        yaml.set("inv.Legacy.armor", emptyList<Any>())
        yaml.set("inv.Legacy.item", emptyList<Any>())
        yaml.save(file)
        val pending = store().pendingRestores()
        assertEquals(1, pending.size)
        assertEquals("Legacy", pending[0].name)
        assertNull(pending[0].uuid)
    }

    @Test
    fun `lobby and sign locations persist`() {
        val s = store()
        s.setLobby(SavedLocation("lobby", 1.0, 2.0, 3.0, 45.5f, 10.25f))
        s.setSign("a1", SavedLocation("world", 5.0, 64.0, 5.0))
        val s2 = store()
        val lobby = s2.lobby()!!
        assertEquals("lobby", lobby.world)
        assertEquals(45.5f, lobby.yaw, 0.001f)
        val sign = s2.signLocation("a1")!!
        assertEquals(5.0, sign.x)
        assertEquals("a1", s2.signOwner("world", 5.0, 64.0, 5.0))
        s2.clearSign("a1")
        assertNull(store().signLocation("a1"))
    }

    @Test
    fun `invalid arena names rejected`() {
        val s = store()
        for (bad in listOf("", "a/b", "a\\b", "a.b", "..", "players", "PLAYERS", "Players", "a\u0000b", "a\u0007b", "x".repeat(65))) {
            assertFalse(s.isValidArenaName(bad), "expected '$bad' rejected")
        }
        assertTrue(s.isValidArenaName("arena-1_2"))
    }

    @Test
    fun `deleteArena removes arena and status files`() {
        val s = store()
        val arena = Arena("a1")
        s.saveArena(arena)
        s.saveStatus(arena)
        assertTrue(File(folder, "arena/a1.yml").exists())
        s.deleteArena("a1")
        assertFalse(File(folder, "arena/a1.yml").exists())
        assertFalse(File(folder, "status/a1.yml").exists())
    }
}
