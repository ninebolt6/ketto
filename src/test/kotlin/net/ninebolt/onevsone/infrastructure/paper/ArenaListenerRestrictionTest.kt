package net.ninebolt.onevsone.infrastructure.paper

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.ninebolt.onevsone.domain.DefeatCause
import net.ninebolt.onevsone.infrastructure.paper.fixtures.TestEnv
import net.ninebolt.onevsone.infrastructure.paper.fixtures.contains
import net.ninebolt.onevsone.infrastructure.paper.fixtures.uuid
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** 状態ごとの制約(ブロック破壊・コマンド)の検証。 */
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
        val event = mockk<BlockBreakEvent>(relaxed = true)
        every { event.player } returns p1
        env.listener.onBreak(event)
        verify(exactly = 0) { event.isCancelled = true }

        val p2 = env.player("Bob")
        env.join(p2, arena)
        env.tick(6)
        env.listener.onBreak(event)
        verify(exactly = 1) { event.isCancelled = true }
    }

    @Test
    fun `commands blocked except in ONEMORE`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val event = mockk<PlayerCommandPreprocessEvent>(relaxed = true)
        every { event.player } returns p1

        env.listener.onCommand(event)
        verify(exactly = 0) { event.isCancelled = true }

        env.join(p1, arena)
        env.listener.onCommand(event)
        verify(exactly = 0) { event.isCancelled = true }

        val p2 = env.player("Bob")
        env.join(p2, arena)
        env.listener.onCommand(event)
        verify(exactly = 1) { event.isCancelled = true }
        verify(exactly = 1) { p1.sendMessage(contains("コマンドは使用できません！")) }
    }

    @Test
    fun `restriction matrix follows live state transitions`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        env.join(p1, arena)

        fun assertState(damageCancelled: Boolean, breakCancelled: Boolean, commandBlocked: Boolean) {
            val damage = mockk<EntityDamageEvent>(relaxed = true)
            every { damage.entity } returns p1
            env.listener.onDamage(damage)
            if (damageCancelled) verify(exactly = 1) { damage.isCancelled = true } else verify(exactly = 0) { damage.isCancelled = true }

            val breaking = mockk<BlockBreakEvent>(relaxed = true)
            every { breaking.player } returns p1
            env.listener.onBreak(breaking)
            if (breakCancelled) verify(exactly = 1) { breaking.isCancelled = true } else verify(exactly = 0) { breaking.isCancelled = true }

            val command = mockk<PlayerCommandPreprocessEvent>(relaxed = true)
            every { command.player } returns p1
            env.listener.onCommand(command)
            if (commandBlocked) verify(exactly = 1) { command.isCancelled = true } else verify(exactly = 0) { command.isCancelled = true }
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
