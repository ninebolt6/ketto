package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.application.ArenaApplicationService
import net.ninebolt.onevsone.application.ArenaRegistry
import net.ninebolt.onevsone.application.JoinReply
import net.ninebolt.onevsone.application.LeaveReply
import net.ninebolt.onevsone.application.PlayerRecoveryService
import net.ninebolt.onevsone.application.port.MatchStateRepository
import net.ninebolt.onevsone.application.port.PlayerStatsRepository
import net.ninebolt.onevsone.domain.ArenaDefinition
import net.ninebolt.onevsone.domain.ArenaId
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.WorldPosition
import net.ninebolt.onevsone.infrastructure.persistence.YamlArenaRepository
import net.ninebolt.onevsone.infrastructure.persistence.YamlMatchStateRepository
import net.ninebolt.onevsone.infrastructure.persistence.YamlPlayerStatsRepository
import net.ninebolt.onevsone.infrastructure.persistence.YamlStore
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFactory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitScheduler
import org.bukkit.scheduler.BukkitTask
import org.bukkit.scoreboard.Scoreboard
import org.bukkit.scoreboard.ScoreboardManager
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.MockedStatic
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.`when`
import java.io.File
import java.util.UUID
import java.util.logging.Logger

/**
 * Bukkit モック上に実アダプター・実サービスを配線する統合テスト環境。
 * Paper 依存は infrastructure テストに限定する。
 */
class TestEnv(folder: File, requiredWins: Int = 3) {
    val server: Server = mock(Server::class.java)
    val plugin: JavaPlugin = mock(JavaPlugin::class.java)
    val scheduler: BukkitScheduler = mock(BukkitScheduler::class.java)
    val scoreboardManager: ScoreboardManager = mock(ScoreboardManager::class.java)
    val itemFactory: ItemFactory = mock(ItemFactory::class.java)
    val bukkit: MockedStatic<Bukkit> = mockStatic(Bukkit::class.java)
    val itemStackConstruction = org.mockito.Mockito.mockConstruction(ItemStack::class.java) { mock, context ->
        val material = context.arguments()[0] as Material
        `when`(mock.type).thenReturn(material)
        `when`(mock.clone()).thenAnswer { item(material) }
        `when`(mock.serialize()).thenReturn(mutableMapOf<String, Any>("type" to material.name))
    }

    val requiredWins = requiredWins
    val messages = Messages("&8[&61vs1&8] ")
    val failures = PluginFailureReporter { plugin.logger }
    val lookup = PaperPlayerLookup(server)
    val playerPort = PaperPlayerAdapter(lookup, server, failures)
    val schedulerPort = PaperScheduler(plugin)

    var store = YamlStore(folder, Logger.getLogger("1vs1-test"))
        private set
    var arenaRepo: YamlArenaRepository = YamlArenaRepository(store)
        private set
    var matchStateRepo: MatchStateRepository = YamlMatchStateRepository(store)
        private set
    var statsRepo: PlayerStatsRepository = YamlPlayerStatsRepository(store)
        private set
    var equipment = PaperEquipmentAdapter(store, lookup, server, messages)
        private set
    var presentation = PaperMatchPresentation(server, messages, arenaRepo, failures)
        private set
    var registry = ArenaRegistry()
        private set
    var recovery = PlayerRecoveryService(equipment, playerPort, arenaRepo, presentation, failures)
        private set
    var service = buildService()
        private set
    var admin = ArenaAdministrationService(registry, arenaRepo, equipment, presentation, service)
        private set
    val listener: ArenaListener by lazy { ArenaListener(service, admin, lookup, messages) }
    val command: OneVsOneCommand by lazy { OneVsOneCommand(plugin, service, admin, messages) }

    private fun buildService() = ArenaApplicationService(
        registry, arenaRepo, matchStateRepo, statsRepo, equipment, playerPort,
        schedulerPort, presentation, recovery, failures, requiredWins
    )

    /** store または各ポートを差し替えて全依存を再構築(障害注入用)。 */
    fun rebuildWith(
        newStore: YamlStore = store,
        matchState: MatchStateRepository = YamlMatchStateRepository(newStore),
        statsRepo: PlayerStatsRepository = YamlPlayerStatsRepository(newStore)
    ) {
        store = newStore
        arenaRepo = YamlArenaRepository(store)
        matchStateRepo = matchState
        this.statsRepo = statsRepo
        equipment = PaperEquipmentAdapter(store, lookup, server, messages)
        presentation = PaperMatchPresentation(server, messages, arenaRepo, failures)
        registry = ArenaRegistry()
        recovery = PlayerRecoveryService(equipment, playerPort, arenaRepo, presentation, failures)
        service = buildService()
        admin = ArenaAdministrationService(registry, arenaRepo, equipment, presentation, service)
    }

    data class TimerRecord(val runnable: BukkitRunnable, val delay: Long, val period: Long, val taskId: Int)

    val timers = mutableListOf<TimerRecord>()
    val cancelledTaskIds = mutableListOf<Int>()
    var nextTaskId = 1
    val oneShots = mutableListOf<Runnable>()
    val players = mutableMapOf<UUID, Player>()
    val boards = mutableListOf<Scoreboard>()
    val worlds = mutableMapOf<String, World>()

    init {
        `when`(plugin.server).thenReturn(server)
        `when`(plugin.isEnabled).thenReturn(true)
        `when`(plugin.logger).thenReturn(Logger.getLogger("1vs1-test"))
        `when`(plugin.dataFolder).thenReturn(folder)
        val config = YamlConfiguration()
        config.set("prefix", "&8[&61vs1&8] ")
        config.set("required-wins", requiredWins)
        `when`(plugin.config).thenReturn(config)

        `when`(server.scheduler).thenReturn(scheduler)
        `when`(server.scoreboardManager).thenReturn(scoreboardManager)
        `when`(server.itemFactory).thenReturn(itemFactory)
        `when`(server.getPlayer(any<UUID>())).thenAnswer { players[it.getArgument(0)] }
        `when`(server.getPlayerExact(anyString())).thenAnswer { invocation ->
            players.values.firstOrNull { it.name == invocation.getArgument<String>(0) }
        }
        `when`(server.getWorld(anyString())).thenAnswer { worlds[it.getArgument(0)] }

        `when`(scoreboardManager.newScoreboard).thenAnswer {
            val board = mock(Scoreboard::class.java)
            `when`(
                board.registerNewObjective(
                    anyString(),
                    any<org.bukkit.scoreboard.Criteria>(),
                    any<net.kyori.adventure.text.Component>()
                )
            ).thenAnswer {
                val objective = mock(org.bukkit.scoreboard.Objective::class.java)
                `when`(objective.getScore(anyString())).thenAnswer { mock(org.bukkit.scoreboard.Score::class.java) }
                objective
            }
            boards += board
            board
        }
        `when`(itemFactory.getItemMeta(any<Material>())).thenAnswer { mock(ItemMeta::class.java) }
        `when`(itemFactory.asMetaFor(any<ItemMeta>(), any<ItemStack>())).thenAnswer { it.getArgument<ItemMeta>(0) }

        bukkit.`when`<Server> { Bukkit.getServer() }.thenReturn(server)
        bukkit.`when`<BukkitScheduler> { Bukkit.getScheduler() }.thenReturn(scheduler)
        bukkit.`when`<ItemFactory> { Bukkit.getItemFactory() }.thenReturn(itemFactory)

        `when`(
            scheduler.runTaskTimer(
                any<org.bukkit.plugin.Plugin>(),
                any<Runnable>(),
                anyLong(),
                anyLong()
            )
        ).thenAnswer {
            val runnable = it.getArgument<Runnable>(1) as BukkitRunnable
            val task = mock(BukkitTask::class.java)
            val id = nextTaskId++
            `when`(task.taskId).thenReturn(id)
            timers += TimerRecord(runnable, it.getArgument(2), it.getArgument(3), id)
            task
        }
        `when`(scheduler.runTask(any<org.bukkit.plugin.Plugin>(), any<Runnable>())).thenAnswer {
            oneShots += it.getArgument<Runnable>(1)
            mock(BukkitTask::class.java)
        }
        `when`(
            scheduler.runTaskLater(
                any<org.bukkit.plugin.Plugin>(),
                any<Runnable>(),
                anyLong()
            )
        ).thenAnswer {
            oneShots += it.getArgument<Runnable>(1)
            mock(BukkitTask::class.java)
        }
        doAnswer { cancelledTaskIds += it.getArgument<Int>(0); null }
            .`when`(scheduler).cancelTask(org.mockito.ArgumentMatchers.anyInt())
    }

    fun item(type: Material): ItemStack {
        val stack = mock(ItemStack::class.java)
        `when`(stack.type).thenReturn(type)
        `when`(stack.clone()).thenAnswer { item(type) }
        `when`(stack.serialize()).thenReturn(mutableMapOf<String, Any>("type" to type.name))
        return stack
    }

    fun world(name: String = "world"): World = worlds.getOrPut(name) {
        mock(World::class.java).also { `when`(it.name).thenReturn(name) }
    }

    fun inventory(contentsSize: Int = 41, armorSize: Int = 4): PlayerInventory {
        val contents = arrayOfNulls<ItemStack>(contentsSize)
        val armor = arrayOfNulls<ItemStack>(armorSize)
        val inv = mock(PlayerInventory::class.java)
        `when`(inv.contents).thenAnswer { contents.clone() }
        doAnswer { invocation ->
            val arr = invocation.getArgument<Array<ItemStack?>>(0)
            for (i in contents.indices) contents[i] = arr.getOrNull(i)
            null
        }.`when`(inv).setContents(any())
        `when`(inv.armorContents).thenAnswer { armor.clone() }
        doAnswer { invocation ->
            val arr = invocation.getArgument<Array<ItemStack?>>(0)
            for (i in armor.indices) armor[i] = arr.getOrNull(i)
            null
        }.`when`(inv).setArmorContents(any())
        doAnswer { invocation ->
            contents[invocation.getArgument(0)] = invocation.getArgument(1)
            null
        }.`when`(inv).setItem(org.mockito.ArgumentMatchers.anyInt(), any())
        doAnswer {
            contents.fill(null)
            armor.fill(null)
            null
        }.`when`(inv).clear()
        return inv
    }

    fun player(name: String, uuid: UUID = UUID.randomUUID(), worldName: String = "world"): Player {
        val w = world(worldName)
        val p = mock(Player::class.java)
        val inv = inventory()
        val spigot = mock(Player.Spigot::class.java)
        `when`(p.uniqueId).thenReturn(uuid)
        `when`(p.name).thenReturn(name)
        `when`(p.inventory).thenReturn(inv)
        `when`(p.isOnline).thenReturn(true)
        `when`(p.world).thenReturn(w)
        `when`(p.location).thenReturn(Location(w, 0.0, 64.0, 0.0))
        `when`(p.spigot()).thenReturn(spigot)
        players[uuid] = p
        return p
    }

    fun removePlayer(p: Player) {
        players.remove(p.uniqueId)
        `when`(p.isOnline).thenReturn(false)
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
        registry.definitions[id] = ArenaDefinition(
            id,
            enabled = enabled,
            spawn1 = WorldPosition("world", 1.0, 64.0, 1.0),
            spawn2 = WorldPosition("world", 2.0, 64.0, 2.0)
        )
        registry.matches[id] = ArenaMatch(id, requiredWins)
        return id
    }

    fun setKit(arena: ArenaId, snapshot: PaperInventorySnapshot) = equipment.putKit(arena, snapshot)

    /** 看板参加と同じ経路で join し、応答メッセージも配送する。 */
    fun join(player: Player, arena: ArenaId): JoinReply {
        val reply = service.join(player.uniqueId, player.name, arena)
        listener.renderJoin(player, arena.name, reply)
        return reply
    }

    /** QuitEvent と同じく切断スコープ内で quit を呼ぶ。 */
    fun quit(player: Player) {
        lookup.scopeQuitting(player) {
            service.quit(player.uniqueId, player.name)
        }
    }

    /** /1vs1 leave と同じく応答メッセージを配送する。 */
    fun leave(player: Player): LeaveReply {
        val reply = service.leave(player.uniqueId)
        when (reply) {
            LeaveReply.Left -> messages.send(player, messages.leftArena)
            LeaveReply.NotWaiting -> messages.send(player, messages.cannotLeave)
            LeaveReply.NotJoined -> messages.send(player, messages.notJoined)
        }
        return reply
    }

    fun state(name: String = "arena1") = service.matchView(name)?.state

    fun close() {
        itemStackConstruction.close()
        bukkit.close()
    }
}
