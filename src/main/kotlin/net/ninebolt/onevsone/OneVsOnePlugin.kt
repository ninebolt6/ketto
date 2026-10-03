package net.ninebolt.onevsone

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.application.ArenaApplicationService
import net.ninebolt.onevsone.application.ArenaLifecycleService
import net.ninebolt.onevsone.application.ArenaRegistry
import net.ninebolt.onevsone.application.ArenaSignService
import net.ninebolt.onevsone.application.LobbyService
import net.ninebolt.onevsone.application.MatchProgressionService
import net.ninebolt.onevsone.application.PlayerRecoveryService
import net.ninebolt.onevsone.application.PlayerStatsService
import net.ninebolt.onevsone.infrastructure.paper.ArenaGuardListener
import net.ninebolt.onevsone.infrastructure.paper.ArenaMatchListener
import net.ninebolt.onevsone.infrastructure.paper.ArenaSignListener
import net.ninebolt.onevsone.infrastructure.paper.ArenaTeleportListener
import net.ninebolt.onevsone.infrastructure.paper.PaperEquipmentAdapter
import net.ninebolt.onevsone.infrastructure.paper.PaperPlayerAdapter
import net.ninebolt.onevsone.infrastructure.paper.PaperPlayerLookup
import net.ninebolt.onevsone.infrastructure.paper.PaperPresentation
import net.ninebolt.onevsone.infrastructure.paper.PaperScheduler
import net.ninebolt.onevsone.infrastructure.paper.PluginSettings
import net.ninebolt.onevsone.infrastructure.paper.command.OneVsOneCommand
import net.ninebolt.onevsone.infrastructure.paper.message.LanguageFiles
import net.ninebolt.onevsone.infrastructure.paper.message.Messenger
import net.ninebolt.onevsone.infrastructure.persistence.SqliteArenaRepository
import net.ninebolt.onevsone.infrastructure.persistence.SqliteArenaSignRepository
import net.ninebolt.onevsone.infrastructure.persistence.SqliteBackupStore
import net.ninebolt.onevsone.infrastructure.persistence.SqliteKitStore
import net.ninebolt.onevsone.infrastructure.persistence.SqliteLobbyRepository
import net.ninebolt.onevsone.infrastructure.persistence.SqlitePlayerStatsRepository
import net.ninebolt.onevsone.infrastructure.persistence.SqliteStore
import org.bukkit.plugin.java.JavaPlugin

// open is required because MockBukkit generates a proxy subclass at load time
open class OneVsOnePlugin : JavaPlugin() {

    private lateinit var module: PluginModule

    override fun onEnable() {
        saveDefaultConfig()
        val settings = PluginSettings.load(config, logger)

        LanguageFiles.syncBundled(dataFolder, logger) { saveResource(it, false) }
        val messenger = Messenger.load(
            messagesDir = LanguageFiles.dir(dataFolder),
            fallbackLang = settings.defaultLanguage,
            language = settings.language,
            logger = logger,
        )

        // the store is created outside the module so it can be closed if wiring fails midway
        val store = SqliteStore(dataFolder, logger)
        try {
            module = PluginModule(this, store, settings.requiredWins, messenger)
            module.registerCommands()
            module.registerListeners()
        } catch (e: Throwable) {
            runCatching { store.close() }
            throw e
        }
    }

    override fun onDisable() {
        if (::module.isInitialized) {
            try {
                module.lifecycle.shutdown()
            } finally {
                module.store.close()
            }
        }
    }
}

private class PluginModule(
    private val plugin: OneVsOnePlugin,
    val store: SqliteStore,
    requiredWins: Int,
    private val messenger: Messenger,
) {
    private val arenaRepository = SqliteArenaRepository(store, plugin.logger)
    private val lobbyRepository = SqliteLobbyRepository(store)
    private val signRepository = SqliteArenaSignRepository(store)
    private val stats = SqlitePlayerStatsRepository(store)

    private val lookup = PaperPlayerLookup(plugin.server)
    private val players = PaperPlayerAdapter(lookup = lookup, server = plugin.server, plugin = plugin, logger = plugin.logger)
    private val equipment = PaperEquipmentAdapter(
        backups = SqliteBackupStore(store, plugin.logger),
        kitStore = SqliteKitStore(store),
        lookup = lookup,
    )
    private val presentation = PaperPresentation(server = plugin.server, messenger = messenger, logger = plugin.logger)

    private val registry = ArenaRegistry(requiredWins)
    private val signs = ArenaSignService(registry = registry, signs = signRepository, presentation = presentation)
    private val recovery = PlayerRecoveryService(
        backups = equipment,
        players = players,
        lobby = lobbyRepository,
        presentation = presentation,
        logger = plugin.logger,
    )
    private val statsService = PlayerStatsService(stats = stats)
    private val progression = MatchProgressionService(
        registry = registry,
        signs = signs,
        stats = statsService,
        kit = equipment,
        players = players,
        scheduler = PaperScheduler(plugin),
        presentation = presentation,
        recovery = recovery,
        logger = plugin.logger,
    )
    private val service = ArenaApplicationService(
        registry = registry,
        players = players,
        recovery = recovery,
        progression = progression,
        signs = signs,
    )
    val lifecycle = ArenaLifecycleService(
        registry = registry,
        arenas = arenaRepository,
        recovery = recovery,
        progression = progression,
        signs = signs,
        logger = plugin.logger,
    )
    private val admin = ArenaAdministrationService(
        registry = registry,
        arenas = arenaRepository,
        signRepo = signRepository,
        kit = equipment,
        progression = progression,
        signs = signs,
    )
    private val lobby = LobbyService(lobby = lobbyRepository)

    init {
        lifecycle.load()
    }

    fun registerCommands() {
        val commands = OneVsOneCommand(
            service = service,
            admin = admin,
            statsService = statsService,
            signs = signs,
            lobby = lobby,
            players = players,
            logger = plugin.logger,
            messenger = messenger,
        )
        plugin.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(commands.node(), "1vs1 arena command")
        }
    }

    fun registerListeners() {
        val manager = plugin.server.pluginManager
        manager.registerEvents(ArenaMatchListener(service, lookup, messenger), plugin)
        manager.registerEvents(ArenaGuardListener(service), plugin)
        manager.registerEvents(ArenaTeleportListener(service, lookup), plugin)
        manager.registerEvents(ArenaSignListener(service, signs, messenger), plugin)
    }
}
