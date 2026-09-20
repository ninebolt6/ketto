package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.fixtures.TestApp
import net.ninebolt.onevsone.application.port.PersistenceFailure
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.DefeatCause
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 中断・切断・永続化失敗など異常系の復元力を検証する。 */
class ArenaApplicationServiceResilienceTest {

    @Test
    fun `abort during countdown stops timer and never equips`() {
        val app = TestApp()
        val (p1, p2) = app.joinedTwo()
        app.scheduler.tick(2)
        app.service.abort(Arena.Id("arena1"))
        assertEquals(ArenaState.WAITING, app.state())
        assertNull(app.service.arenaIdOf(p1.id))
        app.scheduler.tick(6)
        assertEquals(0, app.equipment.backupCalls)
        assertTrue(app.equipment.kitApplies.isEmpty())
        assertTrue(p1.teleports.isEmpty())
        assertTrue(p2.teleports.isEmpty())
        assertTrue(app.equipment.restored.isEmpty())
    }

    @Test
    fun `backup persistence failure aborts before any equipment change`() {
        val app = TestApp()
        app.equipment.failOnBackup = PersistenceFailure("disk full")
        val (p1, p2) = app.joinedTwo()
        app.scheduler.tick(6)
        assertEquals(ArenaState.WAITING, app.state())
        assertNull(app.service.arenaIdOf(p1.id))
        assertNull(app.service.arenaIdOf(p2.id))
        assertTrue(app.equipment.kitApplies.isEmpty())
        assertTrue(app.equipment.restored.isEmpty())
        assertTrue(app.failures.reports.any { it.first.contains("Could not save inventories") })
        app.scheduler.tick(3)
        assertTrue(p1.teleports.isEmpty())
    }

    @Test
    fun `equipment apply failure after backup aborts and restores`() {
        val app = TestApp()
        // 2 回目の applyKit で失敗させる
        app.equipment.failOnApplyAt = 2
        val (p1, p2) = app.joinedTwo()
        app.scheduler.tick(6)
        assertEquals(ArenaState.WAITING, app.state())
        assertNull(app.service.arenaIdOf(p1.id))
        // 取得済みバックアップで両者復元される
        assertEquals(2, app.equipment.restored.size)
        assertTrue(app.failures.reports.any { it.first.contains("Could not apply equipment") })
    }

    @Test
    fun `same tick duplicate defeat does not double score`() {
        val app = TestApp()
        val (p1, p2) = app.startMatch()
        p2.dead = true
        assertTrue(app.service.defeat(p2.id, DefeatCause.DEATH))
        assertFalse(app.service.defeat(p2.id, DefeatCause.DEATH))
        assertFalse(app.service.defeat(p2.id, DefeatCause.FALL))
        assertEquals(1, app.service.matchOf("arena1")!!.winsOf(p1.id))
        assertTrue(app.stats.stats.isEmpty())
    }

    @Test
    fun `countdown aborts when participant disconnects mid countdown`() {
        val app = TestApp()
        val (p1, p2) = app.joinedTwo()
        app.scheduler.tick(2)
        app.players.disconnect(p2)
        app.scheduler.tick()
        // 次回タイマー実行で不在を検出して中断
        assertEquals(ArenaState.WAITING, app.state())
        assertTrue(app.equipment.kitApplies.isEmpty())
    }

    @Test
    fun `stats failure for winner does not block loser record or restores`() {
        val app = TestApp(requiredWins = 1)
        val (p1, p2) = app.startMatch()
        app.stats.failOnWin = PersistenceFailure("write failed")
        assertTrue(app.service.defeat(p2.id, DefeatCause.FALL))
        assertEquals(ArenaState.WAITING, app.state())
        // 敗者側の記録は続行される
        assertEquals(1, app.stats.stats[p2.id]?.losses)
        assertNull(app.stats.stats[p1.id])
        assertEquals(2, app.equipment.restored.size)
        assertTrue(app.failures.reports.any { it.first.startsWith("Failed to record win") })
    }

    @Test
    fun `stats failure for loser does not block winner record`() {
        val app = TestApp(requiredWins = 1)
        val (p1, p2) = app.startMatch()
        app.stats.failOnLoss = PersistenceFailure("write failed")
        assertTrue(app.service.defeat(p2.id, DefeatCause.FALL))
        assertEquals(1, app.stats.stats[p1.id]?.wins)
        assertTrue(app.failures.reports.any { it.first.startsWith("Failed to record loss") })
    }

    @Test
    fun `status save failure does not prevent registration cleanup`() {
        val app = TestApp()
        val (p1, p2) = app.startMatch()
        app.matchState.failOnSaveStatus = true
        assertThrows(PersistenceFailure::class.java) {
            app.service.abort(Arena.Id("arena1"))
        }
        // メモリ上の登録解除は済んでいる
        assertNull(app.service.arenaIdOf(p1.id))
        assertTrue(app.matchState.registrations.isEmpty())
    }

    @Test
    fun `load isolates per arena status persistence failure`() {
        val app = TestApp()
        app.arenas.save(Arena(Arena.Id("broken")))
        app.arenas.save(Arena(Arena.Id("healthy")))
        app.matchState.failOnSaveStatusFor += "broken"
        app.service.load()
        assertEquals(ArenaState.WAITING, app.service.matchOf("broken")!!.state)
        assertEquals(ArenaState.WAITING, app.service.matchOf("healthy")!!.state)
        assertTrue(app.failures.warnings.any { it.contains("broken") })
    }

    @Test
    fun `shutdown restores backups even when a status save fails`() {
        val app = TestApp()
        app.startMatch("arena1")
        val (q1, q2) = app.startMatch("arena2")
        app.matchState.failOnSaveStatusFor += "arena1"
        app.service.shutdown()
        // 失敗した arena1 の後も arena2 の登録解除と復元が続行される
        assertNull(app.service.arenaIdOf(q1.id))
        assertNull(app.service.arenaIdOf(q2.id))
        assertEquals(4, app.equipment.restored.size)
    }

    @Test
    fun `stale countdown callback after abort does nothing`() {
        val app = TestApp()
        val (p1, p2) = app.joinedTwo()
        val timer = app.scheduler.timers.last()
        app.service.abort(Arena.Id("arena1"))
        // 中断後に古いタイマーが走っても自己キャンセルのみ
        timer.run()
        assertTrue(timer.cancelled)
        assertTrue(app.equipment.kitApplies.isEmpty())
        // 新規参加は可能
        val p3 = app.players.add("Carol")
        assertEquals(JoinReply.JoinedWaiting, app.service.join(p3.id, p3.name, Arena.Id("arena1")))
    }

    @Test
    fun `dead player keeps countdown waiting without consuming the start tick`() {
        val app = TestApp()
        val (p1, p2) = app.joinedTwo()
        app.scheduler.tick(5)
        p2.dead = true
        app.scheduler.tick()
        // 死亡中は開始しないがカウントダウンは継続
        assertEquals(ArenaState.COUNTDOWN, app.state())
        assertEquals(0, app.equipment.backupCalls)
        p2.dead = false
        app.scheduler.tick()
        assertEquals(ArenaState.INGAME, app.state())
    }
}
