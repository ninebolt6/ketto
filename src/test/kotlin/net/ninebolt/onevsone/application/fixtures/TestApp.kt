package net.ninebolt.onevsone.application.fixtures

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.application.ArenaApplicationService
import net.ninebolt.onevsone.application.ArenaLifecycleService
import net.ninebolt.onevsone.application.ArenaRegistry
import net.ninebolt.onevsone.application.ArenaSignService
import net.ninebolt.onevsone.application.JoinOutput
import net.ninebolt.onevsone.application.LobbyService
import net.ninebolt.onevsone.application.MatchProgressionService
import net.ninebolt.onevsone.application.PlayerRecoveryService
import net.ninebolt.onevsone.application.PlayerStatsService
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.WorldPosition
import net.ninebolt.onevsone.domain.fixtures.arenaId
import kotlin.test.assertEquals

class TestApp(val requiredWins: Int = 3) {
    val logger = RecordingLogger()
    val registry = ArenaRegistry(requiredWins)
    val arenas = InMemoryArenaRepository()
    val stats = InMemoryPlayerStatsRepository()
    val players = FakePlayers()
    val equipment = FakeEquipment(players)
    val scheduler = FakeScheduler()
    val presentation = RecordingPresentation()
    val recovery = PlayerRecoveryService(equipment, players, arenas, presentation, logger)
    val signs = ArenaSignService(registry, arenas, presentation)
    val progression = MatchProgressionService(
        registry, signs, stats, equipment, players, scheduler, presentation, recovery, logger,
    )
    val service = ArenaApplicationService(
        registry,
        players,
        recovery,
        progression,
        signs,
        logger,
    )
    val lifecycle = ArenaLifecycleService(registry, arenas, signs, recovery, progression, logger)
    val admin = ArenaAdministrationService(registry, arenas, arenas, equipment, progression, signs)
    val lobby = LobbyService(arenas)
    val statsService = PlayerStatsService(stats)

    fun newArena(name: String = "arena1", enabled: Boolean = true): Arena.Id {
        val id = arenaId(name)
        val spawn1 = WorldPosition.new("world", 1.0, 64.0, 1.0)
        val spawn2 = WorldPosition.new("world", 2.0, 64.0, 2.0)
        val arena = if (enabled) {
            Arena.Enabled.restored(id, spawn1, spawn2)
        } else {
            Arena.Disabled.restored(id, spawn1, spawn2)
        }
        registry.installArena(arena, persist = {})
        return id
    }

    fun joinedTwo(arenaName: String = "arena1"): Pair<FakePlayers.FakeHandle, FakePlayers.FakeHandle> {
        newArena(arenaName)
        val p1 = players.add("Alice")
        val p2 = players.add("Bob")
        assertEquals(JoinOutput.JoinedWaiting, service.join(p1.id, p1.name, arenaId(arenaName)))
        assertEquals(JoinOutput.JoinedStarting, service.join(p2.id, p2.name, arenaId(arenaName)))
        return p1 to p2
    }

    fun startMatch(arenaName: String = "arena1"): Pair<FakePlayers.FakeHandle, FakePlayers.FakeHandle> {
        val pair = joinedTwo(arenaName)
        scheduler.tick(6)
        assertEquals(ArenaState.Kind.INGAME, state(arenaName))
        return pair
    }

    fun state(name: String = "arena1") = service.matchOf(name)!!.state.kind
}
