package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.domain.DefeatCause
import net.ninebolt.onevsone.infrastructure.paper.fixtures.TestEnv
import net.ninebolt.onevsone.infrastructure.paper.fixtures.breakEvent
import net.ninebolt.onevsone.infrastructure.paper.fixtures.damageEvent
import net.ninebolt.onevsone.infrastructure.paper.fixtures.drainMessages
import net.ninebolt.onevsone.infrastructure.paper.fixtures.dropEvent
import net.ninebolt.onevsone.infrastructure.paper.fixtures.placeEvent
import net.ninebolt.onevsone.infrastructure.paper.fixtures.plainBlock
import net.ninebolt.onevsone.infrastructure.paper.fixtures.uuid
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** 状態ごとの制約(ブロック破壊・設置・アイテムドロップ・コマンド)の検証。実イベントの isCancelled を見る。 */
class ArenaListenerRestrictionTest {

    @TempDir
    lateinit var folder: File

    private lateinit var env: TestEnv

    @BeforeEach
    fun setup() {
        env = TestEnv(folder)
        env.registerListeners()
    }

    @AfterEach
    fun tearDown() {
        env.close()
    }

    @Test
    fun `break cancelled only in ingame and roundcountdown`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        env.join(p1, arena)
        val block = env.plainBlock()

        val event = breakEvent(p1, block)
        env.fire(event)
        assertEquals(false, event.isCancelled)

        val p2 = env.player("Bob")
        env.join(p2, arena)
        env.tick(6)
        val ingame = breakEvent(p1, block)
        env.fire(ingame)
        assertEquals(true, ingame.isCancelled)
    }

    @Test
    fun `commands blocked except in ONEMORE`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")

        val free = PlayerCommandPreprocessEvent(p1, "/spawn")
        env.fire(free)
        assertEquals(false, free.isCancelled)

        env.join(p1, arena)
        val onemore = PlayerCommandPreprocessEvent(p1, "/spawn")
        env.fire(onemore)
        assertEquals(false, onemore.isCancelled)

        val p2 = env.player("Bob")
        env.join(p2, arena)
        val countdown = PlayerCommandPreprocessEvent(p1, "/spawn")
        env.fire(countdown)
        assertEquals(true, countdown.isCancelled)
        assertTrue(p1.drainMessages().any { it.contains("コマンドは使用できません！") })
    }

    @Test
    fun `item drop cancelled only while equipped`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        env.join(p1, arena)
        val onemore = env.dropEvent(p1)
        env.fire(onemore)
        assertFalse(onemore.isCancelled)

        val p2 = env.player("Bob")
        env.join(p2, arena)
        val countdown = env.dropEvent(p1)
        env.fire(countdown)
        assertFalse(countdown.isCancelled)

        env.tick(6)
        val ingame = env.dropEvent(p1)
        env.fire(ingame)
        assertTrue(ingame.isCancelled)

        env.service.defeat(p2.uuid, DefeatCause.FALL)
        val roundCountdown = env.dropEvent(p1)
        env.fire(roundCountdown)
        assertTrue(roundCountdown.isCancelled)

        val outsider = env.player("Carol")
        val free = env.dropEvent(outsider)
        env.fire(free)
        assertFalse(free.isCancelled)
    }

    @Test
    fun `block place cancelled only while equipped except flint and steel`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        env.join(p1, arena)
        val onemore = env.placeEvent(p1)
        env.fire(onemore)
        assertFalse(onemore.isCancelled)

        val p2 = env.player("Bob")
        env.join(p2, arena)
        val countdown = env.placeEvent(p1)
        env.fire(countdown)
        assertFalse(countdown.isCancelled)

        env.tick(6)
        val ingame = env.placeEvent(p1)
        env.fire(ingame)
        assertTrue(ingame.isCancelled)

        // 着火は許可する
        val flint = env.placeEvent(p1, held = env.item(Material.FLINT_AND_STEEL))
        env.fire(flint)
        assertFalse(flint.isCancelled)

        env.service.defeat(p2.uuid, DefeatCause.FALL)
        val roundCountdown = env.placeEvent(p1)
        env.fire(roundCountdown)
        assertTrue(roundCountdown.isCancelled)
    }

    @Test
    fun `restriction matrix follows live state transitions`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        env.join(p1, arena)
        val block = env.plainBlock()

        fun assertState(damageCancelled: Boolean, breakCancelled: Boolean, commandBlocked: Boolean) {
            val damage = damageEvent(p1)
            env.fire(damage)
            assertEquals(damageCancelled, damage.isCancelled)

            val breaking = breakEvent(p1, block)
            env.fire(breaking)
            assertEquals(breakCancelled, breaking.isCancelled)

            val command = PlayerCommandPreprocessEvent(p1, "/spawn")
            env.fire(command)
            assertEquals(commandBlocked, command.isCancelled)
        }

        assertState(damageCancelled = false, breakCancelled = false, commandBlocked = false) // ONEMORE

        val p2 = env.player("Bob")
        env.join(p2, arena)
        assertState(damageCancelled = false, breakCancelled = false, commandBlocked = true) // COUNTDOWN

        env.tick(6)
        assertState(damageCancelled = false, breakCancelled = true, commandBlocked = true) // INGAME

        env.service.defeat(p2.uuid, DefeatCause.FALL)
        assertState(damageCancelled = true, breakCancelled = true, commandBlocked = true) // ROUNDCOUNTDOWN
    }
}
