package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.fixtures.TestApp
import net.ninebolt.onevsone.application.port.PersistenceException
import net.ninebolt.onevsone.domain.PlayerStats
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerStatsServiceTest {

    @Test
    fun `own stats returns found when stats exist`() {
        val app = TestApp()
        val p = app.players.add("Alice")
        app.stats.stats[p.id] = PlayerStats.restored(p.id, wins = 3, losses = 1)

        assertEquals(StatsOutput.Found(PlayerStats.restored(p.id, 3, 1)), app.statsService.ownStats(p.id))
    }

    @Test
    fun `own stats returns missing when no stats exist`() {
        val app = TestApp()
        val p = app.players.add("Alice")

        assertEquals(StatsOutput.Missing, app.statsService.ownStats(p.id))
    }

    @Test
    fun `own stats returns missing and warns when the read fails`() {
        val app = TestApp()
        val p = app.players.add("Alice")
        app.stats.failOnFind = PersistenceException("read failed")

        assertEquals(StatsOutput.Missing, app.statsService.ownStats(p.id))
        assertTrue(app.logger.warnings.any { it.contains("Could not read stats for ${p.id}") })
    }

    @Test
    fun `lookup resolves a known name to stats`() {
        val app = TestApp()
        val viewer = app.players.add("Viewer")
        val target = app.players.add("Target")
        app.players.offlineIds["Target"] = target.id
        app.stats.stats[target.id] = PlayerStats.restored(target.id, wins = 2, losses = 1)

        var output: StatsOutput? = null
        app.statsService.lookupStats(viewer.id, "Target", nowNanos = 0) { output = it }

        assertEquals(StatsOutput.Found(PlayerStats.restored(target.id, 2, 1)), output)
    }

    @Test
    fun `lookup of an unknown name returns missing`() {
        val app = TestApp()
        val viewer = app.players.add("Viewer")

        var output: StatsOutput? = null
        app.statsService.lookupStats(viewer.id, "Ghost", nowNanos = 0) { output = it }

        assertEquals(StatsOutput.Missing, output)
    }

    @Test
    fun `a repeated lookup inside the window is throttled and released after it`() {
        val app = TestApp()
        val viewer = app.players.add("Viewer")

        var first: StatsOutput? = null
        app.statsService.lookupStats(viewer.id, "Ghost", nowNanos = 0) { first = it }
        var throttled: StatsOutput? = null
        app.statsService.lookupStats(viewer.id, "Ghost", nowNanos = 1) { throttled = it }
        var released: StatsOutput? = null
        app.statsService.lookupStats(viewer.id, "Ghost", nowNanos = 3_000_000_000L) { released = it }

        assertEquals(StatsOutput.Missing, first)
        assertEquals(StatsOutput.Cooldown, throttled)
        assertEquals(StatsOutput.Missing, released)
    }

    @Test
    fun `own stats does not consume the lookup cooldown`() {
        val app = TestApp()
        val viewer = app.players.add("Viewer")

        app.statsService.ownStats(viewer.id)
        app.statsService.ownStats(viewer.id)
        var output: StatsOutput? = null
        app.statsService.lookupStats(viewer.id, "Ghost", nowNanos = 0) { output = it }

        assertEquals(StatsOutput.Missing, output)
    }
}
