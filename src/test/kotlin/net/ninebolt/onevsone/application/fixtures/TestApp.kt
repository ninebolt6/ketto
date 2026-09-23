package net.ninebolt.onevsone.application.fixtures

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.application.ArenaApplicationService
import net.ninebolt.onevsone.application.ArenaRegistry
import net.ninebolt.onevsone.application.JoinReply
import net.ninebolt.onevsone.application.MatchProgressionService
import net.ninebolt.onevsone.application.MatchStateSync
import net.ninebolt.onevsone.application.PlayerRecoveryService
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.WorldPosition
import kotlin.test.assertEquals

class TestApp(val requiredWins: Int = 3) {
    val registry = ArenaRegistry(requiredWins)
    val arenas = InMemoryArenaRepository()
    val matchState = InMemoryMatchStateRepository()
    val stats = InMemoryPlayerStatsRepository()
    val players = FakePlayers()
    val equipment = FakeEquipment(players)
    val scheduler = FakeScheduler()
    val presentation = RecordingPresentation()
    val failures = RecordingFailures()
    val recovery = PlayerRecoveryService(equipment, players, arenas, presentation, failures)
    val stateSync = MatchStateSync(matchState, presentation)
    val progression = MatchProgressionService(
        registry, stateSync, stats, equipment, players, scheduler, presentation, recovery, failures
    )
    val service = ArenaApplicationService(
        registry, arenas, matchState, stats, players, presentation, recovery, failures, progression, stateSync
    )
    val admin = ArenaAdministrationService(registry, arenas, arenas, arenas, equipment, presentation, progression)

    fun newArena(name: String = "arena1", enabled: Boolean = true): Arena.Id {
        val id = Arena.Id.new(name)
        registry.installArena(
            Arena.new(
                id,
                enabled = enabled,
                spawn1 = WorldPosition.new("world", 1.0, 64.0, 1.0),
                spawn2 = WorldPosition.new("world", 2.0, 64.0, 2.0)
            )
        )
        return id
    }

    /** Joins Alice/Bob into arena1 and advances to COUNTDOWN. */
    fun joinedTwo(arenaName: String = "arena1"): Pair<FakePlayers.FakeHandle, FakePlayers.FakeHandle> {
        newArena(arenaName)
        val p1 = players.add("Alice")
        val p2 = players.add("Bob")
        assertEquals(JoinReply.JoinedWaiting, service.join(p1.id, p1.name, Arena.Id.new(arenaName)))
        assertEquals(JoinReply.JoinedStarting, service.join(p2.id, p2.name, Arena.Id.new(arenaName)))
        return p1 to p2
    }

    /** joinedTwo plus draining the initial countdown to reach INGAME. */
    fun startMatch(arenaName: String = "arena1"): Pair<FakePlayers.FakeHandle, FakePlayers.FakeHandle> {
        val pair = joinedTwo(arenaName)
        scheduler.tick(6)
        assertEquals(ArenaState.INGAME, state(arenaName))
        return pair
    }

    fun state(name: String = "arena1") = service.matchOf(name)!!.state
}
