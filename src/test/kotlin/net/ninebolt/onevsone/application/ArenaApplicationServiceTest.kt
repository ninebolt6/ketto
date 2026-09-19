package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.port.PersistenceFailure
import net.ninebolt.onevsone.domain.ArenaId
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.DefeatCause
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArenaApplicationServiceTest {

    private fun joinedTwo(app: TestApp): Pair<FakePlayers.FakeHandle, FakePlayers.FakeHandle> {
        app.newArena()
        val p1 = app.players.add("Alice")
        val p2 = app.players.add("Bob")
        assertEquals(JoinReply.JoinedWaiting, app.service.join(p1.id, p1.name, ArenaId("arena1")))
        assertEquals(JoinReply.JoinedStarting, app.service.join(p2.id, p2.name, ArenaId("arena1")))
        return p1 to p2
    }

    private fun startMatch(app: TestApp): Pair<FakePlayers.FakeHandle, FakePlayers.FakeHandle> {
        val pair = joinedTwo(app)
        app.scheduler.tick(6)
        assertEquals(ArenaState.INGAME, app.state())
        return pair
    }

    @Test
    fun `first join waits and second starts countdown`() {
        val app = TestApp()
        app.newArena()
        val p1 = app.players.add("Alice")
        assertEquals(JoinReply.JoinedWaiting, app.service.join(p1.id, p1.name, ArenaId("arena1")))
        assertEquals(ArenaState.ONEMORE, app.state())
        assertEquals(ArenaId("arena1"), app.service.arenaIdOf(p1.id))

        val p2 = app.players.add("Bob")
        assertEquals(JoinReply.JoinedStarting, app.service.join(p2.id, p2.name, ArenaId("arena1")))
        assertEquals(ArenaState.COUNTDOWN, app.state())
        assertEquals(1, app.scheduler.timers.size)
        assertEquals(10L, app.scheduler.timers.last().delay)
        assertEquals(20L, app.scheduler.timers.last().period)
    }

    @Test
    fun `double join across arenas is rejected by index`() {
        val app = TestApp()
        app.newArena("a1")
        app.newArena("a2")
        val p = app.players.add("Alice")
        assertEquals(JoinReply.JoinedWaiting, app.service.join(p.id, p.name, ArenaId("a1")))
        assertEquals(JoinReply.AlreadyJoined, app.service.join(p.id, p.name, ArenaId("a2")))
        assertEquals(ArenaId("a1"), app.service.arenaIdOf(p.id))
        assertEquals(ArenaState.WAITING, app.service.matchView("a2")!!.state)
    }

    @Test
    fun `join rejects disabled or busy arena`() {
        val app = TestApp()
        app.newArena("enabled")
        app.newArena("disabled", enabled = false)
        val p1 = app.players.add("Alice")
        assertEquals(JoinReply.NotEnabled, app.service.join(p1.id, p1.name, ArenaId("disabled")))
        assertNull(app.service.arenaIdOf(p1.id))

        app.service.join(p1.id, p1.name, ArenaId("enabled"))
        val p2 = app.players.add("Bob")
        app.service.join(p2.id, p2.name, ArenaId("enabled"))
        val p3 = app.players.add("Carol")
        assertEquals(JoinReply.InMatch, app.service.join(p3.id, p3.name, ArenaId("enabled")))
        assertNull(app.service.arenaIdOf(p3.id))
    }

    @Test
    fun `join without touching inventory before match start`() {
        val app = TestApp()
        app.newArena()
        val p1 = app.players.add("Alice")
        val p2 = app.players.add("Bob")
        app.service.join(p1.id, p1.name, ArenaId("arena1"))
        app.service.join(p2.id, p2.name, ArenaId("arena1"))
        app.scheduler.tick(5)
        assertEquals(ArenaState.COUNTDOWN, app.state())
        // 開始前: バックアップもキット適用も走っていない
        assertEquals(0, app.equipment.backupCalls)
        assertTrue(app.equipment.kitApplies.isEmpty())
    }

    @Test
    fun `initial countdown ticks then batch backup then equip and INGAME`() {
        val app = TestApp()
        val (p1, p2) = joinedTwo(app)
        for (n in 5 downTo 1) {
            app.scheduler.tick()
            assertEquals(n, app.presentation.countdownTicks.last().seconds)
        }
        assertEquals(ArenaState.COUNTDOWN, app.state())
        assertEquals(0, app.equipment.backupCalls)

        app.scheduler.tick()
        assertEquals(ArenaState.INGAME, app.state())
        // 一括バックアップ → キット適用 → 状態整備 → テレポートの順序
        assertEquals(1, app.equipment.backupCalls)
        assertEquals(2, app.equipment.kitApplies.size)
        assertEquals(1, app.presentation.matchStarts.size)
        assertTrue(p1.events.contains("teleport"))
        assertTrue(p2.events.contains("teleport"))
        assertEquals(2, app.matchState.registrations.size)
        // バックアップは復元台帳に登録済み
        assertTrue(app.service.pendingRestore(p1.id) != null)
        assertTrue(app.service.pendingRestore(p2.id) != null)
    }

    @Test
    fun `abort during countdown stops timer and never equips`() {
        val app = TestApp()
        val (p1, p2) = joinedTwo(app)
        app.scheduler.tick(2)
        app.service.abort(ArenaId("arena1"))
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
        val (p1, p2) = joinedTwo(app)
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
        val (p1, p2) = joinedTwo(app)
        app.scheduler.tick(6)
        assertEquals(ArenaState.WAITING, app.state())
        assertNull(app.service.arenaIdOf(p1.id))
        // 取得済みバックアップで両者復元される
        assertEquals(2, app.equipment.restored.size)
        assertTrue(app.failures.reports.any { it.first.contains("Could not apply equipment") })
    }

    @Test
    fun `round flow never re-backs-up`() {
        val app = TestApp()
        val (p1, p2) = startMatch(app)
        assertEquals(1, app.equipment.backupCalls)

        assertTrue(app.service.defeat(p2.id, DefeatCause.FALL))
        assertEquals(ArenaState.ROUNDCOUNTDOWN, app.state())
        app.scheduler.tick(8)
        assertEquals(ArenaState.INGAME, app.state())
        assertEquals(1, app.equipment.backupCalls)

        assertTrue(app.service.defeat(p2.id, DefeatCause.FALL))
        app.scheduler.tick(8)
        assertEquals(1, app.equipment.backupCalls)
    }

    @Test
    fun `round countdown timing and release resolution`() {
        val app = TestApp()
        val (p1, p2) = startMatch(app)
        assertTrue(app.service.defeat(p2.id, DefeatCause.FALL))
        assertEquals(ArenaState.ROUNDCOUNTDOWN, app.state())
        assertEquals(1, app.presentation.roundWins.size)
        assertEquals(Triple(listOf(p1.id, p2.id), 1, "Alice"), app.presentation.roundWins.last())

        // 非死亡落下: 次 tick のワンショットで解決ガード解放
        assertFalse(app.service.defeat(p2.id, DefeatCause.FALL))
        app.scheduler.runOneShots()
        assertTrue(app.service.defeat(p2.id, DefeatCause.FALL))
        assertEquals(2, app.service.matchView("arena1")!!.winsOf(p1.id))
    }

    @Test
    fun `same tick duplicate defeat does not double score`() {
        val app = TestApp()
        val (p1, p2) = startMatch(app)
        p2.dead = true
        assertTrue(app.service.defeat(p2.id, DefeatCause.DEATH))
        assertFalse(app.service.defeat(p2.id, DefeatCause.DEATH))
        assertFalse(app.service.defeat(p2.id, DefeatCause.FALL))
        assertEquals(1, app.service.matchView("arena1")!!.winsOf(p1.id))
        assertTrue(app.stats.stats.isEmpty())
    }

    @Test
    fun `final defeat finishes match restores and records stats`() {
        val app = TestApp(requiredWins = 1)
        val (p1, p2) = startMatch(app)
        assertTrue(app.service.defeat(p2.id, DefeatCause.FALL))
        assertEquals(ArenaState.WAITING, app.state())
        assertNull(app.service.arenaIdOf(p1.id))
        assertNull(app.service.arenaIdOf(p2.id))
        assertEquals(listOf(ArenaId("arena1") to "Alice"), app.presentation.champions)
        assertEquals(1, app.stats.stats[p1.id]?.wins)
        assertEquals(1, app.stats.stats[p2.id]?.losses)
        // 両者のバックアップ復元 + ロビー転送 + acknowledge
        assertEquals(2, app.equipment.restored.size)
        assertEquals(2, app.equipment.acknowledged.size)
        assertTrue(app.equipment.storedBackups.isEmpty())
        assertEquals(1, app.presentation.fireworks.size)
    }

    @Test
    fun `quit during countdown forfeits without touching inventories`() {
        val app = TestApp()
        val (p1, p2) = joinedTwo(app)
        app.players.disconnect(p1)
        app.players.quittingScope(p1) {
            app.service.quit(p1.id, p1.name)
        }
        assertEquals(ArenaState.WAITING, app.state())
        assertNull(app.service.arenaIdOf(p2.id))
        // バックアップ未作成なので復元もフォールバックも走らない
        assertTrue(app.equipment.restored.isEmpty())
        assertEquals(1, app.stats.stats[p2.id]?.wins)
        assertEquals(1, app.stats.stats[p1.id]?.losses)
        assertEquals(1, app.presentation.champions.size)
        assertTrue(app.presentation.fireworks.isEmpty())
    }

    @Test
    fun `quit during onemore unregisters without stats`() {
        val app = TestApp()
        app.newArena()
        val p1 = app.players.add("Alice")
        app.service.join(p1.id, p1.name, ArenaId("arena1"))
        app.players.disconnect(p1)
        app.players.quittingScope(p1) {
            app.service.quit(p1.id, p1.name)
        }
        assertEquals(ArenaState.WAITING, app.state())
        assertNull(app.service.arenaIdOf(p1.id))
        assertFalse(app.matchState.registrations.containsKey("Alice"))
        assertTrue(app.stats.stats.isEmpty())
        assertTrue(app.equipment.restored.isEmpty())
    }

    @Test
    fun `leave allowed only while onemore`() {
        val app = TestApp()
        app.newArena()
        val p1 = app.players.add("Alice")
        assertEquals(LeaveReply.NotJoined, app.service.leave(p1.id))
        app.service.join(p1.id, p1.name, ArenaId("arena1"))
        val p2 = app.players.add("Bob")
        app.service.join(p2.id, p2.name, ArenaId("arena1"))
        assertEquals(LeaveReply.NotWaiting, app.service.leave(p1.id))
        assertEquals(ArenaId("arena1"), app.service.arenaIdOf(p1.id))
    }

    @Test
    fun `leave during onemore unregisters and resets`() {
        val app = TestApp()
        app.newArena()
        val p1 = app.players.add("Alice")
        app.service.join(p1.id, p1.name, ArenaId("arena1"))
        assertEquals(LeaveReply.Left, app.service.leave(p1.id))
        assertEquals(ArenaState.WAITING, app.state())
        assertNull(app.service.arenaIdOf(p1.id))
        assertFalse(app.matchState.registrations.containsKey("Alice"))
        assertTrue(app.equipment.restored.isEmpty())
    }

    @Test
    fun `shutdown aborts matches and restores online pendings`() {
        val app = TestApp()
        val (p1, p2) = startMatch(app)
        app.service.shutdown()
        assertEquals(ArenaState.WAITING, app.state())
        assertNull(app.service.arenaIdOf(p1.id))
        // オンラインの両者へ同期復元
        assertEquals(2, app.equipment.restored.size)
        assertTrue(app.matchState.registrations.isEmpty())
    }

    @Test
    fun `countdown aborts when participant disconnects mid countdown`() {
        val app = TestApp()
        val (p1, p2) = joinedTwo(app)
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
        val (p1, p2) = startMatch(app)
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
        val (p1, p2) = startMatch(app)
        app.stats.failOnLoss = PersistenceFailure("write failed")
        assertTrue(app.service.defeat(p2.id, DefeatCause.FALL))
        assertEquals(1, app.stats.stats[p1.id]?.wins)
        assertTrue(app.failures.reports.any { it.first.startsWith("Failed to record loss") })
    }

    @Test
    fun `status save failure does not prevent registration cleanup`() {
        val app = TestApp()
        val (p1, p2) = startMatch(app)
        app.matchState.failOnSaveStatus = true
        org.junit.jupiter.api.Assertions.assertThrows(PersistenceFailure::class.java) {
            app.service.abort(ArenaId("arena1"))
        }
        // メモリ上の登録解除は済んでいる
        assertNull(app.service.arenaIdOf(p1.id))
        assertTrue(app.matchState.registrations.isEmpty())
    }

    @Test
    fun `stale countdown callback after abort does nothing`() {
        val app = TestApp()
        val (p1, p2) = joinedTwo(app)
        val timer = app.scheduler.timers.last()
        app.service.abort(ArenaId("arena1"))
        // 中断後に古いタイマーが走っても自己キャンセルのみ
        timer.run()
        assertTrue(timer.cancelled)
        assertTrue(app.equipment.kitApplies.isEmpty())
        // 新規参加は可能
        val p3 = app.players.add("Carol")
        assertEquals(JoinReply.JoinedWaiting, app.service.join(p3.id, p3.name, ArenaId("arena1")))
    }

    @Test
    fun `dead player keeps countdown waiting without consuming the start tick`() {
        val app = TestApp()
        val (p1, p2) = joinedTwo(app)
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

    @Test
    fun `round countdown restores INGAME and releases resolution at completion`() {
        val app = TestApp()
        val (p1, p2) = startMatch(app)
        app.service.defeat(p2.id, DefeatCause.FALL)
        // tick7: 再装備 / tick50以降: 5→1 / tick150: 再開
        app.scheduler.tick()   // remaining 7: kit reapply
        app.scheduler.tick()   // remaining 6: nothing
        for (n in 5 downTo 1) {
            app.scheduler.tick()
            assertEquals(n, app.presentation.roundCountdownTicks.last().seconds)
        }
        assertEquals(ArenaState.ROUNDCOUNTDOWN, app.state())
        app.scheduler.tick()
        assertEquals(ArenaState.INGAME, app.state())
        assertEquals(1, app.presentation.roundStarts.size)
        // 解決ガードが解放されている
        assertTrue(app.service.defeat(p2.id, DefeatCause.FALL))
    }

    @Test
    fun `forfeit during roundcountdown ends match`() {
        val app = TestApp()
        val (p1, p2) = startMatch(app)
        app.service.defeat(p2.id, DefeatCause.FALL)
        assertEquals(ArenaState.ROUNDCOUNTDOWN, app.state())
        app.players.disconnect(p1)
        app.players.quittingScope(p1) {
            app.service.quit(p1.id, p1.name)
        }
        assertEquals(ArenaState.WAITING, app.state())
        assertEquals(1, app.stats.stats[p2.id]?.wins)
        assertEquals(1, app.stats.stats[p1.id]?.losses)
    }
}
