package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.domain.DefeatCause
import net.ninebolt.onevsone.infrastructure.paper.fixtures.TestEnv
import net.ninebolt.onevsone.infrastructure.paper.fixtures.breakEvent
import net.ninebolt.onevsone.infrastructure.paper.fixtures.damageEvent
import net.ninebolt.onevsone.infrastructure.paper.fixtures.drainMessages
import net.ninebolt.onevsone.infrastructure.paper.fixtures.plainBlock
import net.ninebolt.onevsone.infrastructure.paper.fixtures.uuid
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** 状態ごとの制約(ブロック破壊・コマンド)の検証。実イベントの isCancelled を見る。 */
class ArenaListenerRestrictionTest {

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

    @Test
    fun `break cancelled only in ingame and roundcountdown`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        env.join(p1, arena)
        val block = env.plainBlock()

        val event = breakEvent(p1, block)
        env.listener.onBreak(event)
        assertEquals(false, event.isCancelled)

        val p2 = env.player("Bob")
        env.join(p2, arena)
        env.tick(6)
        val ingame = breakEvent(p1, block)
        env.listener.onBreak(ingame)
        assertEquals(true, ingame.isCancelled)
    }

    @Test
    fun `commands blocked except in ONEMORE`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")

        val free = PlayerCommandPreprocessEvent(p1, "/spawn")
        env.listener.onCommand(free)
        assertEquals(false, free.isCancelled)

        env.join(p1, arena)
        val onemore = PlayerCommandPreprocessEvent(p1, "/spawn")
        env.listener.onCommand(onemore)
        assertEquals(false, onemore.isCancelled)

        val p2 = env.player("Bob")
        env.join(p2, arena)
        val countdown = PlayerCommandPreprocessEvent(p1, "/spawn")
        env.listener.onCommand(countdown)
        assertEquals(true, countdown.isCancelled)
        assertTrue(p1.drainMessages().any { it.contains("コマンドは使用できません！") })
    }

    @Test
    fun `restriction matrix follows live state transitions`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        env.join(p1, arena)
        val block = env.plainBlock()

        fun assertState(damageCancelled: Boolean, breakCancelled: Boolean, commandBlocked: Boolean) {
            val damage = damageEvent(p1)
            env.listener.onDamage(damage)
            assertEquals(damageCancelled, damage.isCancelled)

            val breaking = breakEvent(p1, block)
            env.listener.onBreak(breaking)
            assertEquals(breakCancelled, breaking.isCancelled)

            val command = PlayerCommandPreprocessEvent(p1, "/spawn")
            env.listener.onCommand(command)
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
