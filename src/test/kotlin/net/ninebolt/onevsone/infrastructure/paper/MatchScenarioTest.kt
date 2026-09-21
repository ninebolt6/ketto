package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.WorldPosition
import net.ninebolt.onevsone.infrastructure.paper.fixtures.TestEnv
import net.ninebolt.onevsone.infrastructure.paper.fixtures.attackDamage
import net.ninebolt.onevsone.infrastructure.paper.fixtures.blockOf
import net.ninebolt.onevsone.infrastructure.paper.fixtures.genericDamage
import net.ninebolt.onevsone.infrastructure.paper.fixtures.interact
import net.ninebolt.onevsone.infrastructure.paper.fixtures.mob
import net.ninebolt.onevsone.infrastructure.paper.fixtures.signBlock
import net.ninebolt.onevsone.infrastructure.paper.fixtures.simulation
import net.ninebolt.onevsone.infrastructure.paper.fixtures.twoPlayerIngame
import net.ninebolt.onevsone.infrastructure.paper.fixtures.uuid
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.damage.DamageType
import org.bukkit.event.Event
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerTeleportEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * 看板参加から試合終了までを実アクションで通す E2E シナリオ。
 * requiredWins=1 で1ラウンド完結に短縮する。個別経路の網羅は各リスナーテストが担う。
 */
class MatchScenarioTest {

    @TempDir
    lateinit var folder: File

    private lateinit var env: TestEnv

    @BeforeEach
    fun setup() {
        env = TestEnv(folder, requiredWins = 1)
        env.registerListeners()
    }

    @AfterEach
    fun tearDown() {
        env.close()
    }

    @Test
    fun `sign click to match end`() {
        val arena = env.newArena()
        env.signRepo.setSign("arena1", WorldPosition.new("world", 3.0, 64.0, 3.0))
        env.setKit(arena, PaperInventorySnapshot(items = listOf(env.item(Material.IRON_SWORD))))
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")

        env.fire(interact(p1, env.signBlock(3, 64, 3)))
        assertEquals(ArenaState.ONEMORE, env.state())
        env.fire(interact(p2, env.signBlock(3, 64, 3)))
        assertEquals(ArenaState.COUNTDOWN, env.state())

        env.tick(6)
        assertEquals(ArenaState.INGAME, env.state())
        assertTrue(p1.hasTeleported())
        assertEquals(Material.IRON_SWORD, p1.inventory.contents[0]?.type)

        p2.simulateDamage(100.0, attackDamage(p1))
        assertEquals(ArenaState.WAITING, env.state())
        assertEquals(1, env.statsRepo.find(p1.uuid)!!.wins)
        assertEquals(1, env.statsRepo.find(p2.uuid)!!.losses)
        // 試合終了で参加前の空インベントリへ復元される
        assertNull(p1.inventory.contents[0])
    }

    @Test
    fun `mob attack does not harm the match but lethal fall does`() {
        val (p1, p2) = env.twoPlayerIngame()

        val mobbed = p1.simulateDamage(5.0, attackDamage(env.mob(), DamageType.MOB_ATTACK))
        assertTrue(mobbed.isCancelled)
        assertEquals(20.0, p1.health)
        assertEquals(ArenaState.INGAME, env.state())

        // 環境ダメージは従来通り敗北として受理する
        p1.simulateDamage(100.0, genericDamage())
        assertEquals(ArenaState.WAITING, env.state())
        assertEquals(1, env.statsRepo.find(p2.uuid)!!.wins)
    }

    @Test
    fun `ender pearl escapes nothing but external teleport is blocked`() {
        val (p1, _) = env.twoPlayerIngame()

        p1.clearTeleported()
        p1.teleport(
            Location(env.world(), 5.0, 64.0, 5.0),
            PlayerTeleportEvent.TeleportCause.ENDER_PEARL
        )
        assertTrue(p1.hasTeleported())

        p1.clearTeleported()
        p1.teleport(
            Location(env.world(), 9.0, 64.0, 9.0),
            PlayerTeleportEvent.TeleportCause.COMMAND
        )
        assertFalse(p1.hasTeleported())
    }

    @Test
    fun `countdown disconnect unregisters only`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)
        assertEquals(ArenaState.COUNTDOWN, env.state())

        p2.disconnect()
        // 開始前の切断は敗北ではなく登録解除。残った参加者は待機に戻り、戦績は付かない
        assertEquals(ArenaState.ONEMORE, env.state())
        assertNull(env.service.arenaIdOf(p2.uuid))
        assertNull(env.statsRepo.find(p1.uuid))
        assertNull(env.statsRepo.find(p2.uuid))
    }

    @Test
    fun `ingame disconnect forfeits and reconnect restores inventory`() {
        val (p1, p2) = env.twoPlayerIngame()
        p1.disconnect()
        assertEquals(ArenaState.WAITING, env.state())
        assertEquals(1, env.statsRepo.find(p2.uuid)!!.wins)
        assertEquals(1, env.statsRepo.find(p1.uuid)!!.losses)

        p1.reconnect()
        assertNull(p1.inventory.contents[0])
    }

    @Test
    fun `kit cannot be stashed into a chest`() {
        val (p1, _) = env.twoPlayerIngame()
        // インタラクトで開封自体を拒否
        val interact = interact(p1, env.blockOf(Material.CHEST))
        env.fire(interact)
        assertEquals(Event.Result.DENY, interact.useInteractedBlock())

        // 仮に開かれてもクリック操作は二番手防衛で遮断
        val view = p1.openInventory(env.server.createInventory(null, InventoryType.CHEST))!!
        val click = p1.simulation().simulateInventoryClick(view, 0)
        assertTrue(click.isCancelled)
    }
}
