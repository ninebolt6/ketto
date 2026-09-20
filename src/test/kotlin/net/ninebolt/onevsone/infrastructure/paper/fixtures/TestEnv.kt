package net.ninebolt.onevsone.infrastructure.paper.fixtures

import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.papermc.paper.threadedregions.scheduler.AsyncScheduler
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.application.ArenaApplicationService
import net.ninebolt.onevsone.application.ArenaRegistry
import net.ninebolt.onevsone.application.JoinReply
import net.ninebolt.onevsone.application.LeaveReply
import net.ninebolt.onevsone.application.MatchProgressionService
import net.ninebolt.onevsone.application.MatchStateSync
import net.ninebolt.onevsone.application.PlayerRecoveryService
import net.ninebolt.onevsone.application.port.MatchStateRepository
import net.ninebolt.onevsone.application.port.PlayerStatsRepository
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.WorldPosition
import net.ninebolt.onevsone.infrastructure.paper.ArenaListener
import net.ninebolt.onevsone.infrastructure.paper.ArenaSignListener
import net.ninebolt.onevsone.infrastructure.paper.Messages
import net.ninebolt.onevsone.infrastructure.paper.command.OneVsOneCommand
import net.ninebolt.onevsone.infrastructure.paper.PaperEquipmentAdapter
import net.ninebolt.onevsone.infrastructure.paper.PaperInventorySnapshot
import net.ninebolt.onevsone.infrastructure.paper.PaperMatchPresentation
import net.ninebolt.onevsone.infrastructure.paper.PaperPlayerAdapter
import net.ninebolt.onevsone.infrastructure.paper.PaperPlayerLookup
import net.ninebolt.onevsone.infrastructure.paper.PaperScheduler
import net.ninebolt.onevsone.infrastructure.paper.PluginFailureReporter
import net.ninebolt.onevsone.infrastructure.persistence.YamlArenaRepository
import net.ninebolt.onevsone.infrastructure.persistence.YamlBackupStore
import net.ninebolt.onevsone.infrastructure.persistence.YamlKitStore
import net.ninebolt.onevsone.infrastructure.persistence.YamlLobbyRepository
import net.ninebolt.onevsone.infrastructure.persistence.YamlMatchStateRepository
import net.ninebolt.onevsone.infrastructure.persistence.YamlSignRepository
import net.ninebolt.onevsone.infrastructure.persistence.YamlPlayerStatsRepository
import net.ninebolt.onevsone.infrastructure.persistence.YamlStore
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.configuration.serialization.ConfigurationSerialization
import org.bukkit.scoreboard.Criteria
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.RenderType
import org.bukkit.scoreboard.Scoreboard
import org.mockbukkit.mockbukkit.inventory.ItemStackMock
import org.mockbukkit.mockbukkit.scoreboard.ObjectiveMock
import org.mockbukkit.mockbukkit.scoreboard.ScoreMock
import org.mockbukkit.mockbukkit.scoreboard.ScoreboardManagerMock
import org.mockbukkit.mockbukkit.scoreboard.ScoreboardMock
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.mockbukkit.mockbukkit.plugin.PluginMock
import org.mockbukkit.mockbukkit.world.WorldMock
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.logging.Logger
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

/**
 * MockBukkit 上に実アダプター・実サービスを配線する統合テスト環境。
 * モック呼出ではなく実状態(実インベントリ・実スケジューラ・実イベント)で検証する。
 * Paper 依存は infrastructure テストに限定する。
 */
class TestEnv(val folder: File, val requiredWins: Int = 3) {
    // spyk はオフライン解決・isEnabled・asyncScheduler の障害注入/同期化のみに使い、
    // 未スタブ呼出は全て実動作へ委譲する
    val server: ServerMock = spyk(MockBukkit.mock())
    val plugin: PluginMock = spyk(MockBukkit.createMockPlugin())
    val asyncScheduler: AsyncScheduler = mockk(relaxed = true)
    // ScoreMock.customName が MockBukkit 4.15 未実装のため、スコアボード境界だけは narrow なスタブに留める
    val scoreboardManager: ScoreboardManagerMock = mockk(relaxed = true)
    val boards = mutableListOf<Scoreboard>()

    init {
        // ItemStack.of が ItemStackMock を返すため、YamlConfiguration 経由の往復に登録が必要
        ConfigurationSerialization.registerClass(ItemStackMock::class.java)
        // async 解決は即時実行に潰し、応答側の runTask は実スケジューラ経由で runOneShots が消化する
        every { server.asyncScheduler } returns asyncScheduler
        every { asyncScheduler.runNow(any(), any<java.util.function.Consumer<ScheduledTask>>()) } answers {
            arg<java.util.function.Consumer<ScheduledTask>>(1).accept(mockk(relaxed = true))
            mockk(relaxed = true)
        }
        every { server.scoreboardManager } returns scoreboardManager
        every { scoreboardManager.newScoreboard } answers {
            // Scoreboard は実物のまま使う。ScoreMock.customName 未実装なので
            // validate を呼ばない匿名サブクラスの objective/score に差し替える
            val board = spyk(ScoreboardMock())
            every { board.registerNewObjective(any<String>(), any<Criteria>(), any<Component>()) } answers {
                object : ObjectiveMock(board, arg(0), arg(2), arg(1), RenderType.INTEGER) {
                    override fun setDisplaySlot(slot: DisplaySlot?) {}
                    override fun getScore(entry: String): ScoreMock =
                        object : ScoreMock(this, entry) {
                            override fun customName(customName: Component?) {}
                            override fun setScore(score: Int) {}
                        }
                }
            }
            boards += board
            board
        }
        every { plugin.isEnabled } returns true
    }

    val messages = Messages.load(File(folder, "lang"), "ja", "auto", Logger.getLogger("1vs1-test"))
    val failures = PluginFailureReporter { plugin.logger }
    val lookup = PaperPlayerLookup(server)
    val playerPort = PaperPlayerAdapter(lookup, server, plugin, failures)
    val schedulerPort = PaperScheduler(plugin)

    var store = YamlStore(folder, Logger.getLogger("1vs1-test"))
        private set
    var arenaRepo: YamlArenaRepository = YamlArenaRepository(store)
        private set
    var lobbyRepo: YamlLobbyRepository = YamlLobbyRepository(store)
        private set
    var signRepo: YamlSignRepository = YamlSignRepository(store)
        private set
    var matchStateRepo: MatchStateRepository = YamlMatchStateRepository(store)
        private set
    var statsRepo: PlayerStatsRepository = YamlPlayerStatsRepository(store)
        private set
    var backupStore: YamlBackupStore = YamlBackupStore(store)
        private set
    var kitStore: YamlKitStore = YamlKitStore(store)
        private set
    var equipment = PaperEquipmentAdapter(backupStore, kitStore, lookup)
        private set
    var presentation = PaperMatchPresentation(server, messages, signRepo, failures)
        private set
    var registry = ArenaRegistry(requiredWins)
        private set
    var recovery = PlayerRecoveryService(equipment, playerPort, lobbyRepo, presentation, failures)
        private set
    var progression = buildProgression()
        private set
    var service = buildService()
        private set
    var admin = ArenaAdministrationService(registry, arenaRepo, signRepo, lobbyRepo, equipment, presentation, progression)
        private set
    val listener: ArenaListener by lazy { ArenaListener(service, lookup, messages) }
    val signListener: ArenaSignListener by lazy { ArenaSignListener(service, admin, messages) }
    val command: OneVsOneCommand by lazy { OneVsOneCommand(service, admin, playerPort, failures, messages) }

    private fun buildProgression() = MatchProgressionService(
        registry, MatchStateSync(matchStateRepo, presentation, failures), statsRepo,
        equipment, equipment, playerPort, schedulerPort, presentation, recovery, failures
    )

    private fun buildService() = ArenaApplicationService(
        registry, arenaRepo, matchStateRepo, statsRepo, playerPort,
        presentation, recovery, failures, progression, MatchStateSync(matchStateRepo, presentation, failures)
    )

    /** store または各ポートを差し替えて全依存を再構築(障害注入用)。 */
    fun rebuildWith(
        newStore: YamlStore = store,
        backupStore: YamlBackupStore = YamlBackupStore(newStore),
        matchState: MatchStateRepository = YamlMatchStateRepository(newStore),
        statsRepo: PlayerStatsRepository = YamlPlayerStatsRepository(newStore)
    ) {
        store = newStore
        this.backupStore = backupStore
        kitStore = YamlKitStore(newStore)
        arenaRepo = YamlArenaRepository(store)
        lobbyRepo = YamlLobbyRepository(store)
        signRepo = YamlSignRepository(store)
        matchStateRepo = matchState
        this.statsRepo = statsRepo
        equipment = PaperEquipmentAdapter(backupStore, kitStore, lookup)
        presentation = PaperMatchPresentation(server, messages, signRepo, failures)
        registry = ArenaRegistry(requiredWins)
        recovery = PlayerRecoveryService(equipment, playerPort, lobbyRepo, presentation, failures)
        progression = buildProgression()
        service = buildService()
        admin = ArenaAdministrationService(registry, arenaRepo, signRepo, lobbyRepo, equipment, presentation, progression)
    }

    fun world(name: String = "world"): WorldMock =
        server.getWorld(name) as? WorldMock ?: server.addSimpleWorld(name)

    fun player(name: String, uuid: Uuid = Uuid.random(), worldName: String = "world"): ArenaPlayerMock {
        val w = world(worldName)
        val p = ArenaPlayerMock(server, name, uuid.toJavaUuid())
        p.setLocale(Locale.JAPAN)
        server.addPlayer(p)
        p.setLocation(Location(w, 0.0, 64.0, 0.0))
        return p
    }

    fun item(type: Material): ItemStack = ItemStack.of(type)

    /** 切断。オンライン一覧から外れ isOnline=false になる。quit 処理は quit() で駆動する。 */
    fun removePlayer(p: Player) {
        (p as? PlayerMock)?.disconnect()
    }

    /** カウントダウンタイマー(period=20tick)を n 回発火させる分だけ時間を進める。 */
    fun tick(times: Int = 1) {
        server.scheduler.performTicks(20L * times)
    }

    /** 遅延 0 のワンショット(延期コールバック)を消化する。周期タイマーは発火させない。 */
    fun runOneShots() {
        server.scheduler.waitAsyncTasksFinished()
        server.scheduler.performTicks(1)
    }

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

    fun setKit(arena: Arena.Id, snapshot: PaperInventorySnapshot) = equipment.putKit(arena, snapshot)

    /** 看板参加と同じ経路で join し、応答メッセージも配送する。 */
    fun join(player: Player, arena: Arena.Id = Arena.Id.new("arena1")): JoinReply {
        val reply = service.join(player.uuid, player.name, arena)
        signListener.renderJoin(player, arena.name, reply)
        return reply
    }

    /** QuitEvent と同じく切断スコープ内で quit を呼ぶ。 */
    fun quit(player: Player) {
        lookup.scopeQuitting(player) {
            service.quit(player.uuid, player.name)
        }
    }

    /** /1vs1 leave と同じく応答メッセージを配送する。 */
    fun leave(player: Player): LeaveReply {
        val reply = service.leave(player.uuid)
        when (reply) {
            LeaveReply.Left -> messages.send(player, messages.leftArena)
            LeaveReply.NotWaiting -> messages.send(player, messages.cannotLeave)
            LeaveReply.NotJoined -> messages.send(player, messages.notJoined)
        }
        return reply
    }

    fun state(name: String = "arena1") = service.matchOf(name)?.state

    fun close() {
        MockBukkit.unmock()
    }
}

/**
 * `Player.Spigot.respawn()` は paper-api の既定実装が UnsupportedOperationException を投げ、
 * MockBukkit の PlayerSpigotMock も未実装のため、PlayerMock.respawn() へ委譲する実装を噛ませる。
 */
class ArenaPlayerMock(server: ServerMock, name: String, uuid: UUID) : PlayerMock(server, name, uuid) {
    private val testSpigot = object : Player.Spigot() {
        var respawnCount = 0
        /** respawn 実行時点の先頭スロット。リスポーン→装備復元の順序検証用。 */
        var slotAtRespawn: ItemStack? = null

        override fun respawn() {
            respawnCount++
            slotAtRespawn = this@ArenaPlayerMock.inventory.getItem(0)
            this@ArenaPlayerMock.respawn()
        }
    }

    override fun spigot(): Player.Spigot = testSpigot

    val respawnCount get() = testSpigot.respawnCount
    val slotAtRespawn get() = testSpigot.slotAtRespawn
}

/** モック Player の Bukkit 側 ID(java.util.UUID)をドメインの Uuid へ変換する。 */
internal val Player.uuid: Uuid get() = uniqueId.toKotlinUuid()
