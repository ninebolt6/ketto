package net.ninebolt.onevsone

import net.ninebolt.onevsone.infrastructure.paper.ArenaGuardListener
import net.ninebolt.onevsone.infrastructure.paper.ArenaMatchListener
import net.ninebolt.onevsone.infrastructure.paper.ArenaSignListener
import net.ninebolt.onevsone.infrastructure.paper.ArenaTeleportListener
import net.ninebolt.onevsone.infrastructure.paper.command.OneVsOneCommand
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.vehicle.VehicleEnterEvent
import org.junit.jupiter.api.AfterEach
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock

class OneVsOnePluginTest {

    private lateinit var server: ServerMock

    @BeforeEach
    fun setup() {
        server = MockBukkit.mock()
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    @Test
    fun `plugin enable wires service command and listeners`() {
        val plugin = MockBukkit.load(OneVsOnePlugin::class.java)

        assertTrue(plugin.isEnabled)
        assertTrue(plugin.getCommand("1vs1")?.executor is OneVsOneCommand)

        assertTrue(PlayerDeathEvent.getHandlerList().registeredListeners.any { it.listener is ArenaMatchListener && it.plugin === plugin })
        assertTrue(InventoryClickEvent.getHandlerList().registeredListeners.any { it.listener is ArenaGuardListener && it.plugin === plugin })
        assertTrue(VehicleEnterEvent.getHandlerList().registeredListeners.any { it.listener is ArenaTeleportListener && it.plugin === plugin })
        assertTrue(EntityExplodeEvent.getHandlerList().registeredListeners.any { it.listener is ArenaSignListener && it.plugin === plugin })
    }
}
