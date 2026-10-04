package net.ninebolt.ketto

import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import net.ninebolt.ketto.infrastructure.paper.ArenaGuardListener
import net.ninebolt.ketto.infrastructure.paper.ArenaMatchListener
import net.ninebolt.ketto.infrastructure.paper.ArenaSignListener
import net.ninebolt.ketto.infrastructure.paper.ArenaTeleportListener
import net.ninebolt.ketto.infrastructure.paper.fixtures.drainMessages
import net.ninebolt.ketto.infrastructure.persistence.SqliteArenaRepository
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.vehicle.VehicleEnterEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KettoPluginTest {

    private lateinit var server: ServerMock

    @BeforeEach
    fun setup() {
        server = MockBukkit.mock()
    }

    @AfterEach
    fun tearDown() {
        unmockkConstructor(SqliteArenaRepository::class)
        MockBukkit.unmock()
    }

    @Test
    fun `plugin enable wires service command and listeners`() {
        val plugin = MockBukkit.load(KettoPlugin::class.java)

        assertTrue(plugin.isEnabled)

        val player = server.addPlayer()
        player.performCommand("ketto")
        assertNotNull(server.commandMap.getCommand("ketto"))
        assertTrue(player.drainMessages().any { it.contains("/ketto stats") })

        assertTrue(PlayerDeathEvent.getHandlerList().registeredListeners.any { it.listener is ArenaMatchListener && it.plugin === plugin })
        assertTrue(InventoryClickEvent.getHandlerList().registeredListeners.any { it.listener is ArenaGuardListener && it.plugin === plugin })
        assertTrue(VehicleEnterEvent.getHandlerList().registeredListeners.any { it.listener is ArenaTeleportListener && it.plugin === plugin })
        assertTrue(EntityExplodeEvent.getHandlerList().registeredListeners.any { it.listener is ArenaSignListener && it.plugin === plugin })
    }

    @Test
    fun `failed arena load closes store and permits disable`() {
        mockkConstructor(SqliteArenaRepository::class)
        every { anyConstructed<SqliteArenaRepository>().loadAll() } throws IllegalStateException("boom")

        val failure = assertFailsWith<IllegalStateException> {
            MockBukkit.load(KettoPlugin::class.java)
        }
        assertEquals("boom", failure.message)
        val plugin = server.pluginManager.getPlugin("ketto") as KettoPlugin
        assertTrue(plugin.isEnabled)
        assertFalse(File(plugin.dataFolder, "data.db-wal").exists())

        val disableResult = runCatching {
            server.pluginManager.disablePlugin(plugin)
            MockBukkit.unmock()
        }
        assertTrue(disableResult.isSuccess)
    }
}
