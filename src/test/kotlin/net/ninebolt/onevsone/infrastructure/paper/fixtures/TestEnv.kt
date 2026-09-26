package net.ninebolt.onevsone.infrastructure.paper.fixtures

import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import io.papermc.paper.threadedregions.scheduler.AsyncScheduler
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.text.Component
import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.application.ArenaApplicationService
import net.ninebolt.onevsone.application.ArenaLifecycleService
import net.ninebolt.onevsone.application.ArenaRegistry
import net.ninebolt.onevsone.application.ArenaSignService
import net.ninebolt.onevsone.application.JoinOutput
import net.ninebolt.onevsone.application.LobbyService
import net.ninebolt.onevsone.application.MatchProgressionService
import net.ninebolt.onevsone.application.MatchStateSync
import net.ninebolt.onevsone.application.PlayerRecoveryService
import net.ninebolt.onevsone.application.PlayerStatsService
import net.ninebolt.onevsone.application.port.MatchStateRepository
import net.ninebolt.onevsone.application.port.PlayerStatsRepository
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.WorldPosition
import net.ninebolt.onevsone.infrastructure.paper.ArenaGuardListener
import net.ninebolt.onevsone.infrastructure.paper.ArenaMatchListener
import net.ninebolt.onevsone.infrastructure.paper.ArenaSignListener
import net.ninebolt.onevsone.infrastructure.paper.ArenaTeleportListener
import net.ninebolt.onevsone.infrastructure.paper.PaperEquipmentAdapter
import net.ninebolt.onevsone.infrastructure.paper.PaperInventorySnapshot
import net.ninebolt.onevsone.infrastructure.paper.PaperPlayerAdapter
import net.ninebolt.onevsone.infrastructure.paper.PaperPlayerLookup
import net.ninebolt.onevsone.infrastructure.paper.PaperPresentation
import net.ninebolt.onevsone.infrastructure.paper.PaperScheduler
import net.ninebolt.onevsone.infrastructure.paper.command.OneVsOneCommand
import net.ninebolt.onevsone.infrastructure.paper.message.Messenger
import net.ninebolt.onevsone.infrastructure.persistence.SqliteArenaRepository
import net.ninebolt.onevsone.infrastructure.persistence.SqliteArenaSignRepository
import net.ninebolt.onevsone.infrastructure.persistence.SqliteBackupStore
import net.ninebolt.onevsone.infrastructure.persistence.SqliteKitStore
import net.ninebolt.onevsone.infrastructure.persistence.SqliteLobbyRepository
import net.ninebolt.onevsone.infrastructure.persistence.SqliteMatchStateRepository
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
    val plugin: PluginMock = spyk(
        PluginMock.builder()
            .withOnEnable { host ->
                host.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
                    event.registrar().register(deps.command.node(), "1vs1 arena command")
                }
            }
            .build(),
    )
    val asyncScheduler: AsyncScheduler = mockk(relaxed = true)

    // ScoreMock.customName is unimplemented in MockBukkit 4.15, so only the scoreboard boundary is a narrow stub
    val scoreboardManager: ScoreboardManagerMock = mockk(relaxed = true)
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
        every { plugin.isEnabled } returns true
    }

    val messenger = Messenger.load(File(folder, "messages"), "en", "auto", Logger.getLogger("1vs1-test"))
    val logger = plugin.logger
    val lookup = PaperPlayerLookup(server)
    val playerPort = PaperPlayerAdapter(lookup, server, plugin, logger)
    val schedulerPort = PaperScheduler(plugin)

    private class Deps(
        val store: SqliteStore,
        val backupStore: SqliteBackupStore,
        val kitStore: SqliteKitStore,
        val arenaRepo: SqliteArenaRepository,
        val lobbyRepo: SqliteLobbyRepository,
        val signRepo: SqliteArenaSignRepository,
        val matchStateRepo: MatchStateRepository,
        val statsRepo: PlayerStatsRepository,
        val equipment: PaperEquipmentAdapter,
        val presentation: PaperPresentation,
        val registry: ArenaRegistry,
        val recovery: PlayerRecoveryService,
        val progression: MatchProgressionService,
        val service: ArenaApplicationService,
        val lifecycle: ArenaLifecycleService,
        val admin: ArenaAdministrationService,
        val statsService: PlayerStatsService,
        val signs: ArenaSignService,
        val lobby: LobbyService,
        val signListener: ArenaSignListener,
        val command: OneVsOneCommand,
    )

    private var deps = run {
        val store = SqliteStore(folder, Logger.getLogger("1vs1-test"))
        makeDeps(store, SqliteBackupStore(store), SqliteMatchStateRepository(store), SqlitePlayerStatsRepository(store))
    }

    init {
        registerListeners()
    }

    val store get() = deps.store
    val backupStore get() = deps.backupStore
    val kitStore get() = deps.kitStore
    val arenaRepo get() = deps.arenaRepo
    val lobbyRepo get() = deps.lobbyRepo
    val signRepo get() = deps.signRepo
    val matchStateRepo get() = deps.matchStateRepo
    val statsRepo get() = deps.statsRepo
    val equipment get() = deps.equipment
    val presentation get() = deps.presentation
    val registry get() = deps.registry
    val recovery get() = deps.recovery
    val progression get() = deps.progression
    val service get() = deps.service
    val lifecycle get() = deps.lifecycle
    val admin get() = deps.admin
    val statsService get() = deps.statsService
    val signs get() = deps.signs
    val lobby get() = deps.lobby
    val signListener get() = deps.signListener
    val command get() = deps.command

    private fun makeDeps(
        store: SqliteStore,
        backupStore: SqliteBackupStore,
        matchStateRepo: MatchStateRepository,
        statsRepo: PlayerStatsRepository,
    ): Deps {
        val kitStore = SqliteKitStore(store)
        val arenaRepo = SqliteArenaRepository(store)
        val lobbyRepo = SqliteLobbyRepository(store)
        val signRepo = SqliteArenaSignRepository(store)
        val equipment = PaperEquipmentAdapter(backupStore, kitStore, lookup)
        val presentation = PaperPresentation(server, messenger, logger)
        val registry = ArenaRegistry(requiredWins, logger)
        val signs = ArenaSignService(registry, signRepo, presentation)
        val recovery = PlayerRecoveryService(equipment, playerPort, lobbyRepo, presentation, logger)
        val progression = MatchProgressionService(
            registry, MatchStateSync(matchStateRepo, signs), statsRepo,
            equipment, playerPort, schedulerPort, presentation, recovery, logger,
        )
        val service = ArenaApplicationService(
            registry,
            playerPort,
            recovery,
            progression,
            MatchStateSync(matchStateRepo, signs),
            logger,
        )
        val lifecycle = ArenaLifecycleService(
            registry,
            arenaRepo,
            MatchStateSync(matchStateRepo, signs),
            recovery,
            progression,
            logger,
        )
        val admin = ArenaAdministrationService(
            registry,
            arenaRepo,
            signRepo,
            equipment,
            progression,
            MatchStateSync(matchStateRepo, signs),
        )
        val statsService = PlayerStatsService(statsRepo)
        val lobby = LobbyService(lobbyRepo)
        return Deps(
            store, backupStore, kitStore, arenaRepo, lobbyRepo, signRepo, matchStateRepo, statsRepo,
            equipment, presentation, registry, recovery, progression, service, lifecycle, admin,
            statsService, signs, lobby,
            ArenaSignListener(service, signs, messenger),
            OneVsOneCommand(service, admin, statsService, signs, lobby, playerPort, logger, messenger),
        )
    }

    fun rebuildWith(
        newStore: SqliteStore = deps.store,
        backupStore: SqliteBackupStore = SqliteBackupStore(newStore),
        matchState: MatchStateRepository = SqliteMatchStateRepository(newStore),
        statsRepo: PlayerStatsRepository = SqlitePlayerStatsRepository(newStore),
    ) {
        deps = makeDeps(newStore, backupStore, matchState, statsRepo)
        registerListeners()
    }

    // Registration must unregister first, or rebuildWith would leave handlers holding the old service
    private fun registerListeners() {
        HandlerList.unregisterAll(plugin)
        server.pluginManager.registerEvents(ArenaMatchListener(service, lookup, messenger), plugin)
        server.pluginManager.registerEvents(ArenaGuardListener(service), plugin)
        server.pluginManager.registerEvents(ArenaTeleportListener(service, lookup), plugin)
        server.pluginManager.registerEvents(ArenaSignListener(service, signs, messenger), plugin)
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
        val id = Arena.Id.new(name)
        val spawn1 = WorldPosition.new("world", 1.0, 64.0, 1.0)
        val spawn2 = WorldPosition.new("world", 2.0, 64.0, 2.0)
        val arena = if (enabled) {
            Arena.Enabled.restored(id, spawn1, spawn2)
        } else {
            Arena.Disabled.restored(id, spawn1, spawn2)
        }
        registry.installArena(arena, persist = arenaRepo::save)
        return id
    }

    fun setKit(arena: Arena.Id, snapshot: PaperInventorySnapshot) = equipment.putKit(arena, snapshot)

    fun join(player: Player, arena: Arena.Id = Arena.Id.new("arena1")): JoinOutput {
        val output = service.join(player.uuid, player.name, arena)
        signListener.renderJoin(player, arena.name, output)
        return output
    }

    fun state(name: String = "arena1") = service.matchOf(name)?.state

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
