package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.application.port.BackupRef
import net.ninebolt.onevsone.application.port.PersistenceException
import net.ninebolt.onevsone.domain.MatchId
import net.ninebolt.onevsone.domain.Participant
import net.ninebolt.onevsone.infrastructure.paper.fixtures.ArenaPlayerMock
import net.ninebolt.onevsone.infrastructure.paper.fixtures.TestEnv
import net.ninebolt.onevsone.infrastructure.paper.fixtures.uuid
import net.ninebolt.onevsone.infrastructure.persistence.PersistedBackup
import org.bukkit.Material
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

class PaperEquipmentAdapterTest {

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

    private fun storeBackup(p: ArenaPlayerMock): BackupRef {
        p.inventory.setItem(0, env.item(Material.DIAMOND))
        val snapshot = PaperInventorySnapshot.capture(p.inventory)
        p.inventory.clear()
        val ref = BackupRef.new(MatchId.new(), p.uuid, p.name)
        env.backupStore.saveBackups(listOf(PersistedBackup(ref, snapshot)))
        return ref
    }

    @Test
    fun `backup fails when a participant is offline`() {
        assertFailsWith<PersistenceException> {
            env.equipment.backupBeforeMatch(MatchId.new(), listOf(Participant.new("Ghost")))
        }
    }

    @Test
    fun `restore fails when nothing is stored for the backup`() {
        val p = env.player("Alice")
        val ref = BackupRef.new(MatchId.new(), p.uuid, "Alice")
        assertFailsWith<PersistenceException> { env.equipment.restore(ref) }
    }

    @Test
    fun `restore fails when the backed up player is offline`() {
        val p = env.player("Alice")
        val ref = env.equipment.backupBeforeMatch(MatchId.new(), listOf(Participant.new(p.uuid, "Alice"))).single()
        env.disconnectWithoutQuitHandler(p)
        assertFailsWith<PersistenceException> { env.equipment.restore(ref) }
    }

    @Test
    fun `restore applies the persisted backup when no snapshot is pending`() {
        val p = env.player("Alice")
        val ref = storeBackup(p)
        env.equipment.restore(ref)
        assertEquals(Material.DIAMOND, p.inventory.getItem(0)?.type)
    }

    @Test
    fun `apply kit fails when the player is offline`() {
        val arena = env.newArena()
        assertFailsWith<PersistenceException> { env.equipment.applyKit(arena, Uuid.random()) }
    }

    @Test
    fun `save kit fails when the player is offline`() {
        val arena = env.newArena()
        assertFailsWith<PersistenceException> { env.equipment.saveKit(arena, Uuid.random()) }
    }

    @Test
    fun `apply kit loads the stored kit when none is cached`() {
        val arena = env.newArena()
        val p = env.player("Alice")
        env.kitStore.saveArenaKit(arena.name, PaperInventorySnapshot(items = listOf(env.item(Material.DIAMOND_SWORD))))
        env.equipment.applyKit(arena, p.uuid)
        assertEquals(Material.DIAMOND_SWORD, p.inventory.getItem(0)?.type)
    }
}
