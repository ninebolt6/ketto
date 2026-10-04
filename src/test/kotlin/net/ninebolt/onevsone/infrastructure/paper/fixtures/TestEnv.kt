package net.ninebolt.onevsone.infrastructure.paper.fixtures

import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import io.papermc.paper.threadedregions.scheduler.AsyncScheduler
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.text.Component
import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.application.ArenaLifecycleService
import net.ninebolt.onevsone.application.ArenaSessions
import net.ninebolt.onevsone.application.ArenaSignService
import net.ninebolt.onevsone.application.InventoryRecoveryService
import net.ninebolt.onevsone.application.JoinOutput
import net.ninebolt.onevsone.application.LobbyService
import net.ninebolt.onevsone.application.MatchParticipationService
import net.ninebolt.onevsone.application.MatchProgressionService
import net.ninebolt.onevsone.application.PlayerStatsService
import net.ninebolt.onevsone.application.port.PlayerStatsRepository
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.WorldPosition
import net.ninebolt.onevsone.domain.fixtures.arenaId
import net.ninebolt.onevsone.infrastructure.paper.ArenaGuardListener
import net.ninebolt.onevsone.infrastructure.paper.ArenaMatchListener
import net.ninebolt.onevsone.infrastructure.paper.ArenaSignListener
import net.ninebolt.onevsone.infrastructure.paper.ArenaTeleportListener
import net.ninebolt.onevsone.infrastructure.paper.PaperEquipment
import net.ninebolt.onevsone.infrastructure.paper.PaperInventorySnapshot
import net.ninebolt.onevsone.infrastructure.paper.PaperPlayerLookup
import net.ninebolt.onevsone.infrastructure.paper.PaperPlayers
import net.ninebolt.onevsone.infrastructure.paper.PaperPresentation
import net.ninebolt.onevsone.infrastructure.paper.PaperScheduler
import net.ninebolt.onevsone.infrastructure.paper.command.OneVsOneCommand
import net.ninebolt.onevsone.infrastructure.paper.message.Messenger
import net.ninebolt.onevsone.infrastructure.persistence.SqliteArenaRepository
import net.ninebolt.onevsone.infrastructure.persistence.SqliteArenaSignRepository
import net.ninebolt.onevsone.infrastructure.persistence.SqliteBackupStore
import net.ninebolt.onevsone.infrastructure.persistence.SqliteKitStore
import net.ninebolt.onevsone.infrastructure.persistence.SqliteLobbyRepository
import net.ninebolt.onevsone.infrastructure.persistence.SqlitePlayerStatsRepository
import net.ninebolt.onevsone.infrastructure.persistence.SqliteStore
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.configuration.serialization.ConfigurationSerialization
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import org.bukkit.inventory.ItemStack
import org.bukkit.scoreboard.Criteria
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.RenderType
import org.bukkit.scoreboard.Scoreboard
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.mockbukkit.mockbukkit.inventory.ItemStackMock
import org.mockbukkit.mockbukkit.plugin.PluginMock
import org.mockbukkit.mockbukkit.scoreboard.ObjectiveMock
import org.mockbukkit.mockbukkit.scoreboard.ScoreMock
import org.mockbukkit.mockbukkit.scoreboard.ScoreboardManagerMock
import org.mockbukkit.mockbukkit.scoreboard.ScoreboardMock
import org.mockbukkit.mockbukkit.world.WorldMock
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.function.Consumer
import java.util.logging.Logger
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

class TestEnv(val folder: File, val requiredWins: Int = 3) {
    // spyk is used only for fault injection; unstubbed calls delegate to real behavior
    val server: ServerMock = spyk(MockBukkit.mock())

    // Lifecycle registration is only allowed inside onEnable, so the handler is attached via the builder's enable callback.
    // COMMANDS fires once per server: after the first dispatch the tree is frozen to the deps that were current then.
    val plugin: PluginMock = PluginMock.builder()
        .withOnEnable { host ->
            host.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
                event.registrar().register(deps.command.node(), "1vs1 arena command")
            }
        }
        .build()
    val asyncScheduler: AsyncScheduler = mockk(relaxed = true)

    // ScoreMock.customName is unimplemented in MockBukkit 4.15, so only the scoreboard boundary is a narrow stub
    val scoreboardManager: ScoreboardManagerMock = mockk(relaxed = true)
    val mainBoard = ScoreboardMock()
    val boards = mutableListOf<Scoreboard>()

    init {
        // ItemStack.of returns ItemStackMock, so registration is needed for round-trips through YamlConfiguration
        ConfigurationSerialization.registerClass(ItemStackMock::class.java)
        every { server.asyncScheduler } returns asyncScheduler
        every { asyncScheduler.runNow(any(), any<Consumer<ScheduledTask>>()) } answers {
            arg<Consumer<ScheduledTask>>(1).accept(mockk(relaxed = true))
            mockk(relaxed = true)
        }
        every { server.scoreboardManager } returns scoreboardManager
        every { scoreboardManager.mainScoreboard } returns mainBoard
        every { scoreboardManager.newScoreboard } answers {
            // ScoreMock.customName is unimplemented in MockBukkit, so objective/score are anonymous subclasses that never call validate
            val board = spyk(ScoreboardMock())
            every { board.registerNewObjective(any<String>(), any<Criteria>(), any<Component>()) } answers {
                object : ObjectiveMock(board, arg(0), arg(2), arg(1), RenderType.INTEGER) {
                    override fun setDisplaySlot(slot: DisplaySlot?) {}
                    override fun getScore(entry: String): ScoreMock = object : ScoreMock(this, entry) {
                        override fun customName(customName: Component?) {}
                        override fun setScore(score: Int) {}
                    }
                }
            }
            boards += board
            board
        }
    }

    val messenger = Messenger.load(File(folder, "messages"), "en", "auto", Logger.getLogger("1vs1-test"))
    val logger = plugin.logger
    val lookup = PaperPlayerLookup(server)
    val playerPort = PaperPlayers(lookup, server, plugin, logger)
    val schedulerPort = PaperScheduler(plugin)

    private class Deps(
        val store: SqliteStore,
        val backupStore: SqliteBackupStore,
        val kitStore: SqliteKitStore,
        val arenaRepository: SqliteArenaRepository,
        val lobbyRepository: SqliteLobbyRepository,
        val signRepository: SqliteArenaSignRepository,
        val statsRepository: PlayerStatsRepository,
        val equipment: PaperEquipment,
        val presentation: PaperPresentation,
        val sessions: ArenaSessions,
        val recovery: InventoryRecoveryService,
        val progression: MatchProgressionService,
        val participation: MatchParticipationService,
        val lifecycle: ArenaLifecycleService,
        val administration: ArenaAdministrationService,
        val statsService: PlayerStatsService,
        val signService: ArenaSignService,
        val lobbyService: LobbyService,
        val command: OneVsOneCommand,
    )

    private var deps = run {
        val store = SqliteStore(folder, Logger.getLogger("1vs1-test"))
        makeDeps(store, SqliteBackupStore(store), SqlitePlayerStatsRepository(store))
    }

    init {
        registerListeners()
    }

    val store get() = deps.store
    val backupStore get() = deps.backupStore
    val kitStore get() = deps.kitStore
    val arenaRepository get() = deps.arenaRepository
    val lobbyRepository get() = deps.lobbyRepository
    val signRepository get() = deps.signRepository
    val statsRepository get() = deps.statsRepository
    val equipment get() = deps.equipment
    val presentation get() = deps.presentation
    val sessions get() = deps.sessions
    val recovery get() = deps.recovery
    val progression get() = deps.progression
    val participation get() = deps.participation
    val lifecycle get() = deps.lifecycle
    val administration get() = deps.administration
    val statsService get() = deps.statsService
    val signService get() = deps.signService
    val lobbyService get() = deps.lobbyService
    val command get() = deps.command

    private fun makeDeps(
        store: SqliteStore,
        backupStore: SqliteBackupStore,
        statsRepository: PlayerStatsRepository,
    ): Deps {
        val kitStore = SqliteKitStore(store)
        val arenaRepository = SqliteArenaRepository(store)
        val lobbyRepository = SqliteLobbyRepository(store)
        val signRepository = SqliteArenaSignRepository(store)
        val equipment = PaperEquipment(backupStore, kitStore, lookup)
        val presentation = PaperPresentation(server, messenger, logger)
        val sessions = ArenaSessions(requiredWins)
        val signService = ArenaSignService(sessions, signRepository, presentation)
        val recovery = InventoryRecoveryService(equipment, playerPort, lobbyRepository, presentation, logger)
        val statsService = PlayerStatsService(statsRepository, playerPort, logger)
        val progression = MatchProgressionService(
            sessions, signService, statsService,
            equipment, playerPort, schedulerPort, presentation, recovery, logger,
        )
        val participation = MatchParticipationService(
            sessions,
            playerPort,
            recovery,
            progression,
            signService,
        )
        val lifecycle = ArenaLifecycleService(
            sessions,
            arenaRepository,
            signService,
            recovery,
            progression,
            logger,
        )
        val administration = ArenaAdministrationService(
            sessions,
            arenaRepository,
            signRepository,
            equipment,
            progression,
            signService,
        )
        val lobbyService = LobbyService(lobbyRepository)
        return Deps(
            store, backupStore, kitStore, arenaRepository, lobbyRepository, signRepository, statsRepository,
            equipment, presentation, sessions, recovery, progression, participation, lifecycle, administration,
            statsService, signService, lobbyService,
            OneVsOneCommand(participation, administration, statsService, signService, lobbyService, messenger),
        )
    }

    fun rebuildWith(
        newStore: SqliteStore = deps.store,
        backupStore: SqliteBackupStore = SqliteBackupStore(newStore),
        statsRepository: PlayerStatsRepository = SqlitePlayerStatsRepository(newStore),
    ) {
        deps = makeDeps(newStore, backupStore, statsRepository)
        registerListeners()
    }

    // Registration must unregister first, or rebuildWith would leave handlers holding the old service
    private fun registerListeners() {
        HandlerList.unregisterAll(plugin)
        server.pluginManager.registerEvents(ArenaMatchListener(participation, lookup, messenger), plugin)
        server.pluginManager.registerEvents(ArenaGuardListener(participation), plugin)
        server.pluginManager.registerEvents(ArenaTeleportListener(participation, lookup), plugin)
        server.pluginManager.registerEvents(ArenaSignListener(participation, signService, messenger), plugin)
    }

    fun fire(event: Event) {
        server.pluginManager.callEvent(event)
    }

    fun world(name: String = "world"): WorldMock = server.getWorld(name) as? WorldMock ?: server.addSimpleWorld(name)

    fun player(name: String, uuid: Uuid = Uuid.random(), worldName: String = "world"): ArenaPlayerMock {
        val w = world(worldName)
        val p = ArenaPlayerMock(server, name, uuid.toJavaUuid())
        p.setLocale(Locale.ENGLISH)
        server.addPlayer(p)
        p.setLocation(Location(w, 0.0, 64.0, 0.0))
        return p
    }

    fun item(type: Material): ItemStack = ItemStack.of(type)

    fun disconnectWithoutQuitHandler(player: ArenaPlayerMock) {
        HandlerList.unregisterAll(plugin)
        try {
            player.disconnect()
        } finally {
            registerListeners()
        }
    }

    fun tick(times: Int = 1) {
        server.scheduler.performTicks(20L * times)
    }

    fun runOneShots() {
        server.scheduler.waitAsyncTasksFinished()
        server.scheduler.performTicks(1)
    }

    fun newArena(name: String = "arena1", enabled: Boolean = true): Arena.Id {
        val id = arenaId(name)
        val spawn1 = WorldPosition.new("world", 1.0, 64.0, 1.0)
        val spawn2 = WorldPosition.new("world", 2.0, 64.0, 2.0)
        val arena = if (enabled) {
            Arena.Enabled.restored(id, spawn1, spawn2)
        } else {
            Arena.Disabled.restored(id, spawn1, spawn2)
        }
        arenaRepository.save(arena)
        sessions.installArena(arena)
        return id
    }

    fun setKit(arena: Arena.Id, snapshot: PaperInventorySnapshot) = kitStore.saveArenaKit(arena.name, snapshot)

    fun join(player: Player, arena: Arena.Id = arenaId("arena1")): JoinOutput = participation.join(player.uuid, player.name, arena)

    fun state(name: String = "arena1") = participation.findMatchIn(name)?.state?.kind

    fun close() {
        deps.store.close()
        MockBukkit.unmock()
    }
}

// Player.Spigot.respawn() is unimplemented in both paper-api's default and MockBukkit's PlayerSpigotMock, so a delegating stub is inserted
class ArenaPlayerMock(server: ServerMock, name: String, uuid: UUID) : PlayerMock(server, name, uuid) {
    // getTargetBlockExact is unimplemented in MockBukkit, so this stub returns the looked-at block
    var targetBlock: Block? = null

    override fun getTargetBlockExact(maxDistance: Int): Block? = targetBlock

    private val testSpigot = object : Player.Spigot() {
        var respawnCount = 0
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

internal val Player.uuid: Uuid get() = uniqueId.toKotlinUuid()
