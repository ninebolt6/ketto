package net.ninebolt.ketto

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import net.ninebolt.ketto.application.ArenaAdministrationService
import net.ninebolt.ketto.application.ArenaLifecycleService
import net.ninebolt.ketto.application.ArenaSessions
import net.ninebolt.ketto.application.ArenaSignService
import net.ninebolt.ketto.application.InventoryRecoveryService
import net.ninebolt.ketto.application.LobbyService
import net.ninebolt.ketto.application.MatchParticipationService
import net.ninebolt.ketto.application.MatchProgressionService
import net.ninebolt.ketto.application.PlayerStatsService
import net.ninebolt.ketto.infrastructure.paper.ArenaGuardListener
import net.ninebolt.ketto.infrastructure.paper.ArenaMatchListener
import net.ninebolt.ketto.infrastructure.paper.ArenaSignListener
import net.ninebolt.ketto.infrastructure.paper.ArenaTeleportListener
import net.ninebolt.ketto.infrastructure.paper.PaperEquipment
import net.ninebolt.ketto.infrastructure.paper.PaperPlayerLookup
import net.ninebolt.ketto.infrastructure.paper.PaperPlayers
import net.ninebolt.ketto.infrastructure.paper.PaperPresentation
import net.ninebolt.ketto.infrastructure.paper.PaperScheduler
import net.ninebolt.ketto.infrastructure.paper.PluginSettings
import net.ninebolt.ketto.infrastructure.paper.command.KettoCommand
import net.ninebolt.ketto.infrastructure.paper.message.LanguageFiles
import net.ninebolt.ketto.infrastructure.paper.message.Messenger
import net.ninebolt.ketto.infrastructure.persistence.SqliteArenaRepository
import net.ninebolt.ketto.infrastructure.persistence.SqliteArenaSignRepository
import net.ninebolt.ketto.infrastructure.persistence.SqliteBackupStore
import net.ninebolt.ketto.infrastructure.persistence.SqliteKitStore
import net.ninebolt.ketto.infrastructure.persistence.SqliteLobbyRepository
import net.ninebolt.ketto.infrastructure.persistence.SqlitePlayerStatsRepository
import net.ninebolt.ketto.infrastructure.persistence.SqliteStore
import org.bukkit.plugin.java.JavaPlugin

// open is required because MockBukkit generates a proxy subclass at load time
open class KettoPlugin : JavaPlugin() {

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
    private val plugin: KettoPlugin,
    val store: SqliteStore,
    requiredWins: Int,
    private val messenger: Messenger,
) {
    private val arenaRepository = SqliteArenaRepository(store, plugin.logger)
    private val lobbyRepository = SqliteLobbyRepository(store)
    private val signRepository = SqliteArenaSignRepository(store, plugin.logger)
    private val statsRepository = SqlitePlayerStatsRepository(store)

    private val lookup = PaperPlayerLookup(plugin.server)
    private val players = PaperPlayers(lookup = lookup, server = plugin.server, plugin = plugin, logger = plugin.logger)
    private val equipment = PaperEquipment(
        backupStore = SqliteBackupStore(store, plugin.logger),
        kitStore = SqliteKitStore(store),
        lookup = lookup,
    )
    private val presentation = PaperPresentation(server = plugin.server, messenger = messenger, logger = plugin.logger)

    private val sessions = ArenaSessions(requiredWins)
    private val signService = ArenaSignService(sessions = sessions, signRepository = signRepository, presentationPort = presentation)
    private val recovery = InventoryRecoveryService(
        backupPort = equipment,
        playerPort = players,
        lobbyRepository = lobbyRepository,
        presentationPort = presentation,
        logger = plugin.logger,
    )
    private val statsService = PlayerStatsService(statsRepository = statsRepository, playerPort = players, logger = plugin.logger)
    private val progression = MatchProgressionService(
        sessions = sessions,
        signService = signService,
        statsService = statsService,
        kitPort = equipment,
        playerPort = players,
        schedulerPort = PaperScheduler(plugin),
        presentationPort = presentation,
        recovery = recovery,
        logger = plugin.logger,
    )
    private val participation = MatchParticipationService(
        sessions = sessions,
        playerPort = players,
        recovery = recovery,
        progression = progression,
        signService = signService,
    )
    val lifecycle = ArenaLifecycleService(
        sessions = sessions,
        arenaRepository = arenaRepository,
        recovery = recovery,
        progression = progression,
        signService = signService,
        logger = plugin.logger,
    )
    private val administration = ArenaAdministrationService(
        sessions = sessions,
        arenaRepository = arenaRepository,
        signRepository = signRepository,
        kitPort = equipment,
        progression = progression,
        signService = signService,
    )
    private val lobbyService = LobbyService(lobbyRepository = lobbyRepository)

    init {
        lifecycle.load()
    }

    fun registerCommands() {
        val commands = KettoCommand(
            participation = participation,
            administration = administration,
            statsService = statsService,
            signService = signService,
            lobbyService = lobbyService,
            messenger = messenger,
        )
        plugin.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(commands.node(), "ketto arena command")
        }
    }

    fun registerListeners() {
        val manager = plugin.server.pluginManager
        manager.registerEvents(ArenaMatchListener(participation, lookup, messenger), plugin)
        manager.registerEvents(ArenaGuardListener(participation), plugin)
        manager.registerEvents(ArenaTeleportListener(participation, lookup), plugin)
        manager.registerEvents(ArenaSignListener(participation, signService, messenger), plugin)
    }
}
