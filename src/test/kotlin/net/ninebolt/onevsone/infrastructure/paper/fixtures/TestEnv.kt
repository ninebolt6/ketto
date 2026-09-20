package net.ninebolt.onevsone.infrastructure.paper.fixtures

import io.mockk.EqMatcher
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic
import io.papermc.paper.threadedregions.scheduler.AsyncScheduler
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.application.ArenaApplicationService
import net.ninebolt.onevsone.application.ArenaRegistry
import net.ninebolt.onevsone.application.JoinReply
import net.ninebolt.onevsone.application.LeaveReply
import net.ninebolt.onevsone.application.MatchProgressionService
import net.ninebolt.onevsone.application.PlayerRecoveryService
import net.ninebolt.onevsone.application.port.MatchStateRepository
import net.ninebolt.onevsone.application.port.PlayerStatsRepository
import net.ninebolt.onevsone.domain.ArenaDefinition
import net.ninebolt.onevsone.domain.ArenaId
import net.ninebolt.onevsone.domain.ArenaMatch
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
import net.ninebolt.onevsone.infrastructure.persistence.YamlLobbyRepository
import net.ninebolt.onevsone.infrastructure.persistence.YamlMatchStateRepository
import net.ninebolt.onevsone.infrastructure.persistence.YamlSignRepository
import net.ninebolt.onevsone.infrastructure.persistence.YamlPlayerStatsRepository
import net.ninebolt.onevsone.infrastructure.persistence.YamlStore
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitScheduler
import org.bukkit.scheduler.BukkitTask
import org.bukkit.scoreboard.Criteria
import org.bukkit.scoreboard.Objective
import org.bukkit.scoreboard.Score
import org.bukkit.scoreboard.Scoreboard
import org.bukkit.scoreboard.ScoreboardManager
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.function.Consumer
import java.util.logging.Logger
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

/**
 * Bukkit モック上に実アダプター・実サービスを配線する統合テスト環境。
 * Paper 依存は infrastructure テストに限定する。
 */
class TestEnv(val folder: File, val requiredWins: Int = 3) {
    val server: Server = mockk(relaxed = true)
    val plugin: JavaPlugin = mockk(relaxed = true)
    val scheduler: BukkitScheduler = mockk(relaxed = true)
    val asyncScheduler: AsyncScheduler = mockk(relaxed = true)
    val scoreboardManager: ScoreboardManager = mockk(relaxed = true)
    val console: ConsoleCommandSender = mockk(relaxed = true)

    val messages = Messages.load(File(folder, "lang"), "ja", "auto", Logger.getLogger("1vs1-test"))
    val failures = PluginFailureReporter { plugin.logger }
    val lookup = PaperPlayerLookup(server)
    val playerPort = PaperPlayerAdapter(lookup, server, failures)
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
    var equipment = PaperEquipmentAdapter(store, lookup, messages)
        private set
    var presentation = PaperMatchPresentation(server, messages, signRepo, failures)
        private set
    var registry = ArenaRegistry()
        private set
    var recovery = PlayerRecoveryService(equipment, playerPort, lobbyRepo, presentation, failures)
        private set
    var service = buildService()
        private set
    var admin = ArenaAdministrationService(registry, arenaRepo, signRepo, lobbyRepo, equipment, presentation, service)
        private set
    val listener: ArenaListener by lazy { ArenaListener(service, lookup, messages) }
    val signListener: ArenaSignListener by lazy { ArenaSignListener(service, admin, messages) }
    val command: OneVsOneCommand by lazy { OneVsOneCommand(plugin, service, admin, messages) }

    private fun buildService(): ArenaApplicationService {
        val progression = MatchProgressionService(
            registry, matchStateRepo, statsRepo, equipment, equipment, playerPort,
            schedulerPort, presentation, recovery, failures
        )
        return ArenaApplicationService(
            registry, arenaRepo, matchStateRepo, statsRepo, playerPort,
            presentation, recovery, failures, progression, requiredWins
        )
    }

    /** store または各ポートを差し替えて全依存を再構築(障害注入用)。 */
    fun rebuildWith(
        newStore: YamlStore = store,
        matchState: MatchStateRepository = YamlMatchStateRepository(newStore),
        statsRepo: PlayerStatsRepository = YamlPlayerStatsRepository(newStore)
    ) {
        store = newStore
        arenaRepo = YamlArenaRepository(store)
        lobbyRepo = YamlLobbyRepository(store)
        signRepo = YamlSignRepository(store)
        matchStateRepo = matchState
        this.statsRepo = statsRepo
        equipment = PaperEquipmentAdapter(store, lookup, messages)
        presentation = PaperMatchPresentation(server, messages, signRepo, failures)
        registry = ArenaRegistry()
        recovery = PlayerRecoveryService(equipment, playerPort, lobbyRepo, presentation, failures)
        service = buildService()
        admin = ArenaAdministrationService(registry, arenaRepo, signRepo, lobbyRepo, equipment, presentation, service)
    }

    data class TimerRecord(val runnable: Runnable, val delay: Long, val period: Long, val taskId: Int)

    val timers = mutableListOf<TimerRecord>()
    val cancelledTaskIds = mutableListOf<Int>()
    var nextTaskId = 1
    val oneShots = mutableListOf<Runnable>()
    val players = mutableMapOf<UUID, Player>()
    val boards = mutableListOf<Scoreboard>()
    val worlds = mutableMapOf<String, World>()

    init {
        mockkStatic(Bukkit::class, ItemStack::class)
        every { Bukkit.getServer() } returns server
        every { Bukkit.getScheduler() } returns scheduler
        every { Bukkit.getScoreboardCriteria(any<String>()) } answers { mockk<Criteria>(relaxed = true) }
        every { ItemStack.of(any<Material>()) } answers { mockk(relaxed = true) }
        every { ItemStack.of(any<Material>(), any<Int>()) } answers { mockk(relaxed = true) }

        mockkConstructor(ItemStack::class)
        every { anyConstructed<ItemStack>().itemMeta = any() } just Runs
        every { anyConstructed<ItemStack>().editMeta(any<Consumer<ItemMeta>>()) } answers {
            firstArg<Consumer<ItemMeta>>().accept(mockk<ItemMeta>(relaxed = true))
            true
        }
        listOf(Material.COMPASS, Material.FEATHER).forEach { material ->
            every { constructedWith<ItemStack>(EqMatcher(material)).type } returns material
            every { constructedWith<ItemStack>(EqMatcher(material)).clone() } answers { item(material) }
            every { constructedWith<ItemStack>(EqMatcher(material)).serialize() } returns mutableMapOf<String, Any>("type" to material.name)
        }

        every { plugin.server } returns server
        every { plugin.isEnabled } returns true
        every { plugin.logger } returns Logger.getLogger("1vs1-test")
        every { plugin.dataFolder } returns folder
        val config = YamlConfiguration()
        config.set("required-wins", requiredWins)
        every { plugin.config } returns config

        every { server.scheduler } returns scheduler
        every { server.asyncScheduler } returns asyncScheduler
        every { server.scoreboardManager } returns scoreboardManager
        every { server.getPlayer(any<UUID>()) } answers { players[firstArg()] }
        every { server.getPlayerExact(any<String>()) } answers {
            players.values.firstOrNull { it.name == firstArg<String>() }
        }
        every { server.onlinePlayers } answers { players.values.toList() }
        every { server.consoleSender } returns console
        every { server.getWorld(any<String>()) } answers { worlds[firstArg()] }
        every { server.getOfflinePlayerIfCached(any<String>()) } returns null

        every { scoreboardManager.newScoreboard } answers {
            val board = mockk<Scoreboard>(relaxed = true)
            every {
                board.registerNewObjective(
                    any<String>(),
                    any<Criteria>(),
                    any<Component>()
                )
            } answers {
                val objective = mockk<Objective>(relaxed = true)
                every { objective.getScore(any<String>()) } answers { mockk<Score>(relaxed = true) }
                objective
            }
            boards += board
            board
        }
        every {
            scheduler.runTaskTimer(
                any<Plugin>(),
                any<Runnable>(),
                any<Long>(),
                any<Long>()
            )
        } answers {
            val runnable = arg<Runnable>(1)
            val task = mockk<BukkitTask>(relaxed = true)
            val id = nextTaskId++
            every { task.taskId } returns id
            every { task.cancel() } answers { cancelledTaskIds += id }
            timers += TimerRecord(runnable, arg(2), arg(3), id)
            task
        }
        every { scheduler.runTask(any<Plugin>(), any<Runnable>()) } answers {
            oneShots += arg<Runnable>(1)
            mockk<BukkitTask>(relaxed = true)
        }
        every {
            scheduler.runTaskLater(
                any<Plugin>(),
                any<Runnable>(),
                any<Long>()
            )
        } answers {
            oneShots += arg<Runnable>(1)
            mockk<BukkitTask>(relaxed = true)
        }
        every { scheduler.cancelTask(any<Int>()) } answers {
            cancelledTaskIds += firstArg<Int>()
        }

        // async タスクは即時実行に潰す。応答側の runTask が oneShots へ溜まるので runOneShots で同期化できる
        every { asyncScheduler.runNow(any<Plugin>(), any<Consumer<ScheduledTask>>()) } answers {
            val task = mockk<ScheduledTask>(relaxed = true)
            arg<Consumer<ScheduledTask>>(1).accept(task)
            task
        }
    }

    fun item(type: Material): ItemStack {
        val stack = mockk<ItemStack>(relaxed = true)
        every { stack.type } returns type
        every { stack.clone() } answers { item(type) }
        every { stack.serialize() } returns mutableMapOf<String, Any>("type" to type.name)
        return stack
    }

    fun world(name: String = "world"): World = worlds.getOrPut(name) {
        mockk<World>(relaxed = true).also { every { it.name } returns name }
    }

    fun inventory(contentsSize: Int = 41, armorSize: Int = 4): PlayerInventory {
        val contents = arrayOfNulls<ItemStack>(contentsSize)
        val armor = arrayOfNulls<ItemStack>(armorSize)
        val inv = mockk<PlayerInventory>(relaxed = true)
        every { inv.contents } answers { contents.clone() }
        every { inv.contents = any() } answers {
            val arr = firstArg<Array<ItemStack?>>()
            contents.indices.forEach { contents[it] = arr.getOrNull(it) }
        }
        every { inv.armorContents } answers { armor.clone() }
        every { inv.armorContents = any() } answers {
            val arr = firstArg<Array<ItemStack?>>()
            armor.indices.forEach { armor[it] = arr.getOrNull(it) }
        }
        every { inv.setItem(any<Int>(), any<ItemStack>()) } answers {
            contents[firstArg()] = arg<ItemStack?>(1)
        }
        every { inv.clear() } answers {
            contents.fill(null)
            armor.fill(null)
        }
        return inv
    }

    fun player(name: String, uuid: Uuid = Uuid.random(), worldName: String = "world"): Player {
        val w = world(worldName)
        val p = mockk<Player>(relaxed = true)
        val inv = inventory()
        val spigot = mockk<Player.Spigot>(relaxed = true)
        every { p.uniqueId } returns uuid.toJavaUuid()
        every { p.name } returns name
        every { p.inventory } returns inv
        every { p.isOnline } returns true
        every { p.world } returns w
        every { p.locale() } returns Locale.JAPAN
        every { p.location } returns Location(w, 0.0, 64.0, 0.0)
        every { p.spigot() } returns spigot
        every { p.getAttribute(any()) } returns null
        players[uuid.toJavaUuid()] = p
        return p
    }

    fun removePlayer(p: Player) {
        players.remove(p.uniqueId)
        every { p.isOnline } returns false
    }

    fun tick(times: Int = 1) {
        repeat(times) { timers.lastOrNull()?.runnable?.run() }
    }

    fun runOneShots() {
        val pending = oneShots.toList()
        oneShots.clear()
        pending.forEach { it.run() }
    }

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

    fun setKit(arena: ArenaId, snapshot: PaperInventorySnapshot) = equipment.putKit(arena, snapshot)

    /** 看板参加と同じ経路で join し、応答メッセージも配送する。 */
    fun join(player: Player, arena: ArenaId): JoinReply {
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
        unmockkConstructor(ItemStack::class)
        unmockkStatic(Bukkit::class, ItemStack::class)
    }
}

/** モック Player の Bukkit 側 ID(java.util.UUID)をドメインの Uuid へ変換する。 */
internal val Player.uuid: Uuid get() = uniqueId.toKotlinUuid()
