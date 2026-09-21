package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.WorldPosition
import net.ninebolt.onevsone.infrastructure.paper.fixtures.TestEnv
import net.ninebolt.onevsone.infrastructure.paper.fixtures.attackDamage
import net.ninebolt.onevsone.infrastructure.paper.fixtures.interact
import net.ninebolt.onevsone.infrastructure.paper.fixtures.signBlock
import net.ninebolt.onevsone.infrastructure.paper.fixtures.twoPlayerIngame
import net.ninebolt.onevsone.infrastructure.paper.fixtures.uuid
import org.bukkit.Material
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * ユーザージャーニーの通し検証(看板参加→終了、切断→復帰)を実アクションで行う E2E。
 * requiredWins=1 で1ラウンド完結に短縮する。単一機構の網羅は各リスナーテストが担う。
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
}
