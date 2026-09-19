package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.port.ArenaRepository
import net.ninebolt.onevsone.application.port.BackupRef
import net.ninebolt.onevsone.application.port.Cancellation
import net.ninebolt.onevsone.application.port.FailureReporter
import net.ninebolt.onevsone.application.port.MatchPresentationPort
import net.ninebolt.onevsone.application.port.MatchStateRepository
import net.ninebolt.onevsone.application.port.PersistenceFailure
import net.ninebolt.onevsone.application.port.PlayerEquipmentPort
import net.ninebolt.onevsone.application.port.PlayerHandle
import net.ninebolt.onevsone.application.port.PlayerPort
import net.ninebolt.onevsone.application.port.PlayerStatsRepository
import net.ninebolt.onevsone.application.port.SchedulerPort
import net.ninebolt.onevsone.domain.ArenaDefinition
import net.ninebolt.onevsone.domain.ArenaId
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.MatchId
import net.ninebolt.onevsone.domain.Participant
import net.ninebolt.onevsone.domain.PlayerStats
import net.ninebolt.onevsone.domain.WorldPosition
import java.util.UUID

/** 手動 tick 駆動のスケジューラ。cancelled な timer も run() で本体を起動する(自己ガード検証のため)。 */
class FakeScheduler : SchedulerPort {
    class Timer(
        val id: Int,
        val delay: Long,
        val period: Long,
        private val owner: FakeScheduler,
        private val action: (Cancellation) -> Unit
    ) : Cancellation {
        var cancelled = false
            private set

        override fun cancel() {
            if (!cancelled) {
                cancelled = true
                owner.cancelledIds += id
            }
        }

        fun run() = action(this)
    }

    class OneShot(val action: () -> Unit) : Cancellation {
        var cancelled = false
            private set

        override fun cancel() {
            cancelled = true
        }

        fun run() {
            if (!cancelled) action()
        }
    }

    var nextId = 1
    val timers = mutableListOf<Timer>()
    val oneShots = mutableListOf<OneShot>()
    val cancelledIds = mutableListOf<Int>()

    override fun schedule(delayTicks: Long, action: () -> Unit): Cancellation =
        OneShot(action).also { oneShots += it }

    override fun repeat(initialDelayTicks: Long, periodTicks: Long, action: (Cancellation) -> Unit): Cancellation =
        Timer(nextId++, initialDelayTicks, periodTicks, this, action).also { timers += it }

    fun tick(times: Int = 1) {
        repeat(times) { timers.lastOrNull()?.run() }
    }

    fun runOneShots() {
        val pending = oneShots.toList()
        oneShots.clear()
        pending.forEach { it.run() }
    }
}

/** インメモリのプレイヤー操作ポート。 */
class FakePlayers : PlayerPort {
    class FakeHandle(
        override val id: UUID,
        override val name: String
    ) : PlayerHandle {
        override var online = true
        override var dead = false
        var quitting = false
        var positionValue: WorldPosition? = WorldPosition("world", 0.0, 64.0, 0.0)
        val teleports = mutableListOf<WorldPosition>()
        val events = mutableListOf<String>()

        override fun position(): WorldPosition? = positionValue
        override fun respawn() {
            if (dead) {
                dead = false
                events += "respawn"
            }
        }

        override fun resetVitals() {
            if (!dead) events += "vitals"
        }

        override fun prepareForMatch() {
            if (!dead) events += "prepare"
        }

        override fun teleport(position: WorldPosition) {
            teleports += position
            events += "teleport"
        }
    }

    val players = mutableMapOf<UUID, FakeHandle>()

    override fun handle(playerId: UUID): PlayerHandle? =
        players[playerId]?.takeIf { it.online || it.quitting }

    fun add(name: String, id: UUID = UUID.randomUUID()): FakeHandle =
        FakeHandle(id, name).also { players[id] = it }

    fun disconnect(handle: FakeHandle) {
        handle.online = false
    }

    /** QuitEvent 中の切断者解決を再現するスコープ。 */
    fun <R> quittingScope(handle: FakeHandle, block: () -> R): R {
        handle.quitting = true
        try {
            return block()
        } finally {
            handle.quitting = false
        }
    }
}

/** インベントリ操作の記録・障害注入用フェイク。実データは持たず BackupRef のみ。 */
class FakeEquipment(var players: FakePlayers? = null) : PlayerEquipmentPort {
    val storedBackups = linkedMapOf<UUID, BackupRef>()
    val restored = mutableListOf<BackupRef>()
    val acknowledged = mutableListOf<BackupRef>()
    val kitApplies = mutableListOf<Pair<ArenaId, UUID>>()
    val savedKits = mutableListOf<Pair<ArenaId, UUID>>()
    var backupCalls = 0
    var applyCalls = 0
    var failOnBackup: PersistenceFailure? = null
    var failOnApplyAt: Int = -1
    var failOnAcknowledge = false
    var failOnRestore = false

    override fun backupBeforeMatch(match: MatchId, participants: List<Participant>): List<BackupRef> {
        backupCalls++
        failOnBackup?.let { throw it }
        return participants.map { p ->
            BackupRef(UUID.randomUUID(), match, p.id, p.name)
        }.onEach { storedBackups[it.backupId] = it }
    }

    override fun restore(backup: BackupRef) {
        if (failOnRestore) throw PersistenceFailure("restore failed")
        restored += backup
        backup.playerId?.let { players?.players?.get(it)?.events?.add("restore") }
    }

    override fun acknowledge(backup: BackupRef) {
        if (failOnAcknowledge) throw PersistenceFailure("acknowledge failed")
        acknowledged += backup
        storedBackups.remove(backup.backupId)
    }

    override fun pendingBackups(): List<BackupRef> = storedBackups.values.toList()

    fun seedBackup(ref: BackupRef) {
        storedBackups[ref.backupId] = ref
    }

    override fun applyKit(arena: ArenaId, playerId: UUID) {
        applyCalls++
        if (applyCalls == failOnApplyAt) throw PersistenceFailure("kit apply failed")
        kitApplies += arena to playerId
    }

    override fun saveKit(arena: ArenaId, playerId: UUID) {
        savedKits += arena to playerId
    }
}

class InMemoryArenaRepository : ArenaRepository {
    val names = mutableListOf<String>()
    val definitions = mutableMapOf<String, ArenaDefinition>()
    var lobbyPosition: WorldPosition? = null
    val signs = mutableMapOf<String, WorldPosition>()
    var failOnSave = false

    override fun arenaNames(): List<String> = names.toList()
    override fun saveArenaNames(names: List<String>) {
        this.names.clear()
        this.names += names
    }

    override fun find(name: String): ArenaDefinition? = definitions[name] ?: ArenaDefinition(ArenaId(name))
    override fun save(arena: ArenaDefinition) {
        if (failOnSave) throw PersistenceFailure("save failed")
        definitions[arena.name] = arena
    }

    override fun delete(name: String) {
        definitions.remove(name)
    }

    override fun lobby(): WorldPosition? = lobbyPosition
    override fun setLobby(position: WorldPosition) {
        lobbyPosition = position
    }

    override fun signLocation(arenaName: String): WorldPosition? = signs[arenaName]
    override fun setSign(arenaName: String, position: WorldPosition) {
        signs[arenaName] = position
    }

    override fun clearSign(arenaName: String) {
        signs.remove(arenaName)
    }

    override fun signOwner(world: String, x: Double, y: Double, z: Double): String? =
        signs.entries.firstOrNull { (_, pos) ->
            pos.world == world && pos.x == x && pos.y == y && pos.z == z
        }?.key
}

class InMemoryMatchStateRepository : MatchStateRepository {
    val savedViews = mutableListOf<ArenaMatch>()
    val registrations = linkedMapOf<String, ArenaId>()
    var failOnRegister = false
    var failOnUnregister = false
    var failOnSaveStatus = false

    override fun saveStatus(match: ArenaMatch) {
        if (failOnSaveStatus) throw PersistenceFailure("status save failed")
        savedViews += match
    }

    override fun registerParticipant(participant: Participant, arena: ArenaId) {
        if (failOnRegister) throw PersistenceFailure("register failed")
        registrations[participant.name] = arena
    }

    override fun unregisterParticipant(playerName: String) {
        if (failOnUnregister) throw PersistenceFailure("unregister failed")
        registrations.remove(playerName)
    }

    override fun clearRegistrations() {
        registrations.clear()
    }
}

class InMemoryPlayerStatsRepository : PlayerStatsRepository {
    val stats = mutableMapOf<UUID, PlayerStats>()
    var failOnWin: Throwable? = null
    var failOnLoss: Throwable? = null
    var failOnFind: Throwable? = null

    override fun find(playerId: UUID): PlayerStats? {
        failOnFind?.let { throw it }
        return stats[playerId]
    }

    override fun recordWin(playerId: UUID) {
        failOnWin?.let { throw it }
        val s = stats[playerId] ?: PlayerStats(0, 0)
        stats[playerId] = PlayerStats(s.wins + 1, s.losses)
    }

    override fun recordLoss(playerId: UUID) {
        failOnLoss?.let { throw it }
        val s = stats[playerId] ?: PlayerStats(0, 0)
        stats[playerId] = PlayerStats(s.wins, s.losses + 1)
    }
}

class RecordingPresentation : MatchPresentationPort {
    data class Countdown(val ids: List<UUID>, val seconds: Int)

    val countdownTicks = mutableListOf<Countdown>()
    val roundCountdownTicks = mutableListOf<Countdown>()
    val matchStarts = mutableListOf<List<UUID>>()
    val roundStarts = mutableListOf<List<UUID>>()
    val roundWins = mutableListOf<Triple<List<UUID>, Int, String>>()
    val roundEndSounds = mutableListOf<WorldPosition>()
    val champions = mutableListOf<Pair<ArenaId, String>>()
    val fireworks = mutableListOf<UUID>()
    val scoreboards = mutableListOf<ArenaMatch>()
    val clearedScoreboards = mutableListOf<UUID>()
    val signUpdates = mutableListOf<Pair<ArenaId, ArenaState>>()

    override fun countdownTick(participantIds: List<UUID>, secondsLeft: Int) {
        countdownTicks += Countdown(participantIds, secondsLeft)
    }

    override fun roundCountdownTick(participantIds: List<UUID>, secondsLeft: Int) {
        roundCountdownTicks += Countdown(participantIds, secondsLeft)
    }

    override fun matchStart(participantIds: List<UUID>) {
        matchStarts += participantIds
    }

    override fun roundStart(participantIds: List<UUID>) {
        roundStarts += participantIds
    }

    override fun roundWon(participantIds: List<UUID>, round: Int, winnerName: String) {
        roundWins += Triple(participantIds, round, winnerName)
    }

    override fun roundEndSound(position: WorldPosition) {
        roundEndSounds += position
    }

    override fun champion(arena: ArenaId, winnerName: String) {
        champions += arena to winnerName
    }

    override fun championFirework(playerId: UUID) {
        fireworks += playerId
    }

    override fun updateScoreboard(match: ArenaMatch) {
        scoreboards += match
    }

    override fun clearScoreboard(playerId: UUID) {
        clearedScoreboards += playerId
    }

    override fun updateSign(arena: ArenaId, state: ArenaState) {
        signUpdates += arena to state
    }
}

class RecordingFailures : FailureReporter {
    val warnings = mutableListOf<String>()
    val reports = mutableListOf<Pair<String, Throwable>>()

    override fun warn(message: String) {
        warnings += message
    }

    override fun report(context: String, error: Throwable) {
        reports += context to error
    }
}

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
    val service = ArenaApplicationService(
        registry, arenas, matchState, stats, equipment, players, scheduler, presentation, recovery, failures, requiredWins
    )
    val admin = ArenaAdministrationService(registry, arenas, equipment, presentation, service)

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

    fun state(name: String = "arena1") = service.matchOf(name)!!.state
}
