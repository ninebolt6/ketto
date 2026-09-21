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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock

/**
 * composition root を実ロードして配線を検証するスモークテスト。
 * TestEnv の手動ミラーでは検出できない onEnable の登録忘れを塞ぐ。
 */
class OneVsOnePluginTest {

    private lateinit var server: ServerMock

    @BeforeEach
    fun setup() {
        server = MockBukkit.mock()
    }

    @AfterEach
    fun tearDown() {
        // onDisable → service.shutdown() の実経路も通す
        MockBukkit.unmock()
    }

    @Test
    fun `plugin enable wires service command and listeners`() {
        val plugin = MockBukkit.load(OneVsOnePlugin::class.java)

        assertTrue(plugin.isEnabled)
        assertNotNull(plugin.service)
        assertTrue(plugin.getCommand("1vs1")?.executor is OneVsOneCommand)

        // 各リスナー固有のイベントの HandlerList で登録を確認する
        assertTrue(PlayerDeathEvent.getHandlerList().registeredListeners.any { it.listener is ArenaMatchListener && it.plugin === plugin })
        assertTrue(InventoryClickEvent.getHandlerList().registeredListeners.any { it.listener is ArenaGuardListener && it.plugin === plugin })
        assertTrue(VehicleEnterEvent.getHandlerList().registeredListeners.any { it.listener is ArenaTeleportListener && it.plugin === plugin })
        assertTrue(EntityExplodeEvent.getHandlerList().registeredListeners.any { it.listener is ArenaSignListener && it.plugin === plugin })
    }
}
