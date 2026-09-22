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
import net.ninebolt.onevsone.infrastructure.paper.ArenaGuardListener
import net.ninebolt.onevsone.infrastructure.paper.ArenaMatchListener
import net.ninebolt.onevsone.infrastructure.paper.ArenaSignListener
import net.ninebolt.onevsone.infrastructure.paper.ArenaTeleportListener
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
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
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
import java.util.function.Consumer
import java.util.Locale
import java.util.UUID
import java.util.logging.Logger
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

/**
 * Integration test environment wiring real adapters and real services on
 * MockBukkit. Verifies real state (real inventories, real scheduler, real
 * events) rather than mock calls. Paper dependencies are confined to
 * infrastructure tests.
 */
class TestEnv(val folder: File, val requiredWins: Int = 3) {
    // spyk is used only for fault injection/synchronization of offline
    // resolution, isEnabled, and asyncScheduler; unstubbed calls delegate to real behavior
    val server: ServerMock = spyk(MockBukkit.mock())
    val plugin: PluginMock = spyk(MockBukkit.createMockPlugin())
    val asyncScheduler: AsyncScheduler = mockk(relaxed = true)
    // ScoreMock.customName is unimplemented in MockBukkit 4.15, so only the scoreboard boundary is a narrow stub
    val scoreboardManager: ScoreboardManagerMock = mockk(relaxed = true)
    val boards = mutableListOf<Scoreboard>()

    init {
        // ItemStack.of returns ItemStackMock, so registration is needed for round-trips through YamlConfiguration
        ConfigurationSerialization.registerClass(ItemStackMock::class.java)
        // Async resolution collapses to immediate execution; the reply side's runTask is drained via the real scheduler by runOneShots
        every { server.asyncScheduler } returns asyncScheduler
        every { asyncScheduler.runNow(any(), any<Consumer<ScheduledTask>>()) } answers {
            arg<Consumer<ScheduledTask>>(1).accept(mockk(relaxed = true))
            mockk(relaxed = true)
        }
        every { server.scoreboardManager } returns scoreboardManager
        every { scoreboardManager.newScoreboard } answers {
            // The Scoreboard itself stays real. Since ScoreMock.customName is
            // unimplemented, objective/score are swapped for anonymous
            // subclasses that never call validate
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
    var recovery = PlayerRecoveryService(equipment, playerPort, lobbyRepo, presentation, failures, server.onlineMode)
        private set
    var progression = buildProgression()
        private set
    var service = buildService()
        private set
    var admin = ArenaAdministrationService(registry, arenaRepo, signRepo, lobbyRepo, equipment, presentation, progression)
        private set
    // These are eager vars reassigned on rebuild so they always reference the current dependencies.
    // command keeps its instance within an environment because it holds the stats rate-limit state
    var signListener = ArenaSignListener(service, admin, messages)
        private set
    var command = OneVsOneCommand(service, admin, playerPort, failures, messages)
        private set

    private fun buildProgression() = MatchProgressionService(
        registry, MatchStateSync(matchStateRepo, presentation), statsRepo,
        equipment, equipment, playerPort, schedulerPort, presentation, recovery, failures
    )

    private fun buildService() = ArenaApplicationService(
        registry, arenaRepo, matchStateRepo, statsRepo, playerPort,
        presentation, recovery, failures, progression, MatchStateSync(matchStateRepo, presentation)
    )

    /** Rebuilds all dependencies with the store or individual ports swapped out (for fault injection). */
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
        recovery = PlayerRecoveryService(equipment, playerPort, lobbyRepo, presentation, failures, server.onlineMode)
        progression = buildProgression()
        service = buildService()
        admin = ArenaAdministrationService(registry, arenaRepo, signRepo, lobbyRepo, equipment, presentation, progression)
        signListener = ArenaSignListener(service, admin, messages)
        command = OneVsOneCommand(service, admin, playerPort, failures, messages)
    }

    /**
     * Registers listeners through the real dispatch path. To prevent
     * re-registration after rebuildWith from leaving handlers holding the old
     * service, registered handlers are unregistered first and recreated from
     * the current dependencies.
     */
    fun registerListeners() {
        HandlerList.unregisterAll(plugin)
        server.pluginManager.registerEvents(ArenaMatchListener(service, lookup, messages), plugin)
        server.pluginManager.registerEvents(ArenaGuardListener(service), plugin)
        server.pluginManager.registerEvents(ArenaTeleportListener(service, lookup), plugin)
        server.pluginManager.registerEvents(ArenaSignListener(service, admin, messages), plugin)
    }

    /** Fires an event through the real dispatch path of the registered listeners. */
    fun fire(event: Event) {
        server.pluginManager.callEvent(event)
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

    /** Disconnects: removed from the online list, isOnline=false. Quit handling is driven by quit(). */
    fun removePlayer(p: Player) {
        (p as? PlayerMock)?.disconnect()
    }

    /** Advances time enough to fire the countdown timer (period=20 ticks) n times. */
    fun tick(times: Int = 1) {
        server.scheduler.performTicks(20L * times)
    }

    /** Drains delay-0 one-shots (deferred callbacks) without firing periodic timers. */
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

    /** Joins via the same path as sign-join and also delivers the reply messages. */
    fun join(player: Player, arena: Arena.Id = Arena.Id.new("arena1")): JoinReply {
        val reply = service.join(player.uuid, player.name, arena)
        signListener.renderJoin(player, arena.name, reply)
        return reply
    }

    /**
     * Calls quit inside a disconnect scope, same as QuitEvent.
     * In an environment with listeners registered, a disconnect()ed player's
     * quit is already driven through the real event path, so calling this too
     * double-invokes it (currently idempotent, but muddies intent).
     */
    fun quit(player: Player) {
        lookup.scopeQuitting(player) {
            service.quit(player.uuid, player.name)
        }
    }

    /** Delivers reply messages, same as /1vs1 leave. */
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
 * `Player.Spigot.respawn()` throws UnsupportedOperationException from the
 * paper-api default implementation, and MockBukkit's PlayerSpigotMock is also
 * unimplemented, so this inserts an implementation delegating to
 * PlayerMock.respawn().
 */
class ArenaPlayerMock(server: ServerMock, name: String, uuid: UUID) : PlayerMock(server, name, uuid) {
    /** The looked-at block. getTargetBlockExact is unimplemented in MockBukkit, so this stub returns it. */
    var targetBlock: Block? = null

    override fun getTargetBlockExact(maxDistance: Int): Block? = targetBlock

    private val testSpigot = object : Player.Spigot() {
        var respawnCount = 0
        /** The first slot at the moment respawn runs. For verifying the respawn -> restore ordering. */
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
