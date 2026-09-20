package net.ninebolt.onevsone.application.fixtures

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.application.ArenaApplicationService
import net.ninebolt.onevsone.application.ArenaRegistry
import net.ninebolt.onevsone.application.JoinReply
import net.ninebolt.onevsone.application.MatchProgressionService
import net.ninebolt.onevsone.application.MatchStateSync
import net.ninebolt.onevsone.application.PlayerRecoveryService
import net.ninebolt.onevsone.domain.ArenaDefinition
import net.ninebolt.onevsone.domain.ArenaId
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.WorldPosition
import org.junit.jupiter.api.Assertions.assertEquals

/** テスト向けにまとめて配線するコンテナ。 */
class TestApp(val requiredWins: Int = 3) {
    val registry = ArenaRegistry()
    val arenas = InMemoryArenaRepository()
    val matchState = InMemoryMatchStateRepository()
    val stats = InMemoryPlayerStatsRepository()
    val players = FakePlayers()
    val equipment = FakeEquipment(players)
    val scheduler = FakeScheduler()
    val presentation = RecordingPresentation()
    val failures = RecordingFailures()
    val recovery = PlayerRecoveryService(equipment, players, arenas, presentation, failures)
    val stateSync = MatchStateSync(matchState, presentation, failures)
    val progression = MatchProgressionService(
        registry, stateSync, stats, equipment, equipment, players, scheduler, presentation, recovery, failures
    )
    val service = ArenaApplicationService(
        registry, arenas, matchState, stats, players, presentation, recovery, failures, progression, stateSync, requiredWins
    )
    val admin = ArenaAdministrationService(registry, arenas, arenas, arenas, equipment, presentation, service)

    fun newArena(name: String = "arena1", enabled: Boolean = true): ArenaId {
        val id = ArenaId(name)
        registry.putDefinition(
            ArenaDefinition(
                id,
                enabled = enabled,
                spawn1 = WorldPosition("world", 1.0, 64.0, 1.0),
                spawn2 = WorldPosition("world", 2.0, 64.0, 2.0)
            )
        )
        registry.installMatch(ArenaMatch(id, requiredWins))
        return id
    }

    /** arena1 へ Alice/Bob を参加させ COUNTDOWN まで進める。 */
    fun joinedTwo(arenaName: String = "arena1"): Pair<FakePlayers.FakeHandle, FakePlayers.FakeHandle> {
        newArena(arenaName)
        val p1 = players.add("Alice")
        val p2 = players.add("Bob")
        assertEquals(JoinReply.JoinedWaiting, service.join(p1.id, p1.name, ArenaId(arenaName)))
        assertEquals(JoinReply.JoinedStarting, service.join(p2.id, p2.name, ArenaId(arenaName)))
        return p1 to p2
    }

    /** joinedTwo のうえ初回カウントダウンを消化して INGAME にする。 */
    fun startMatch(arenaName: String = "arena1"): Pair<FakePlayers.FakeHandle, FakePlayers.FakeHandle> {
        val pair = joinedTwo(arenaName)
        scheduler.tick(6)
        assertEquals(ArenaState.INGAME, state(arenaName))
        return pair
    }

    fun state(name: String = "arena1") = service.matchOf(name)!!.state
}
