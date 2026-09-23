package net.ninebolt.onevsone

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.application.ArenaApplicationService
import net.ninebolt.onevsone.application.ArenaLifecycleService
import net.ninebolt.onevsone.application.ArenaRegistry
import net.ninebolt.onevsone.application.ArenaSignService
import net.ninebolt.onevsone.application.LobbyService
import net.ninebolt.onevsone.application.MatchProgressionService
import net.ninebolt.onevsone.application.MatchStateSync
import net.ninebolt.onevsone.application.PlayerRecoveryService
import net.ninebolt.onevsone.application.PlayerStatsService
import net.ninebolt.onevsone.infrastructure.paper.ArenaGuardListener
import net.ninebolt.onevsone.infrastructure.paper.ArenaMatchListener
import net.ninebolt.onevsone.infrastructure.paper.ArenaSignListener
import net.ninebolt.onevsone.infrastructure.paper.ArenaTeleportListener
import net.ninebolt.onevsone.infrastructure.paper.message.LanguageFiles
import net.ninebolt.onevsone.infrastructure.paper.message.Messenger
import net.ninebolt.onevsone.infrastructure.paper.command.OneVsOneCommand
import net.ninebolt.onevsone.infrastructure.paper.PaperEquipmentAdapter
import net.ninebolt.onevsone.infrastructure.paper.PaperPresentation
import net.ninebolt.onevsone.infrastructure.paper.PaperPlayerAdapter
import net.ninebolt.onevsone.infrastructure.paper.PaperPlayerLookup
import net.ninebolt.onevsone.infrastructure.paper.PaperScheduler
import net.ninebolt.onevsone.infrastructure.paper.PluginFailureReporter
import net.ninebolt.onevsone.infrastructure.paper.PluginSettings
import net.ninebolt.onevsone.infrastructure.persistence.YamlArenaRepository
import net.ninebolt.onevsone.infrastructure.persistence.YamlBackupStore
import net.ninebolt.onevsone.infrastructure.persistence.YamlKitStore
import net.ninebolt.onevsone.infrastructure.persistence.YamlLobbyRepository
import net.ninebolt.onevsone.infrastructure.persistence.YamlMatchStateRepository
import net.ninebolt.onevsone.infrastructure.persistence.YamlSignRepository
import net.ninebolt.onevsone.infrastructure.persistence.YamlPlayerStatsRepository
import net.ninebolt.onevsone.infrastructure.persistence.YamlStore
import org.bukkit.plugin.java.JavaPlugin

/**
 * Composition root. Reads config values, manually instantiates and injects the
 * implementations, and registers events and commands. The main FQCN is kept.
 */
// open is required because MockBukkit generates a proxy subclass at load time
open class OneVsOnePlugin : JavaPlugin() {

    lateinit var service: ArenaApplicationService
        private set

    private lateinit var lifecycle: ArenaLifecycleService

    override fun onEnable() {
        saveDefaultConfig()
        val settings = PluginSettings.load(config, logger)

        LanguageFiles.syncBundled(dataFolder, logger) { saveResource(it, false) }
        val messenger = Messenger.load(
            messagesDir = LanguageFiles.dir(dataFolder),
            fallbackLang = settings.defaultLanguage,
            language = settings.language,
            logger = logger
        )

        val failures = PluginFailureReporter { logger }
        val store = YamlStore(dataFolder, logger)
        val arenaRepository = YamlArenaRepository(store)
        val lobbyRepository = YamlLobbyRepository(store)
        val signRepository = YamlSignRepository(store)
        val matchState = YamlMatchStateRepository(store)
        val stats = YamlPlayerStatsRepository(store)

        val lookup = PaperPlayerLookup(server)
        val playerPort = PaperPlayerAdapter(lookup = lookup, server = server, plugin = this, failures = failures)
        val equipment = PaperEquipmentAdapter(
            backups = YamlBackupStore(store),
            kitStore = YamlKitStore(store),
            lookup = lookup
        )
        val presentation = PaperPresentation(server = server, messenger = messenger, failures = failures)

        val registry = ArenaRegistry(settings.requiredWins)
        val signs = ArenaSignService(registry = registry, signs = signRepository, presentation = presentation)
        val recovery = PlayerRecoveryService(
            backups = equipment,
            players = playerPort,
            lobby = lobbyRepository,
            presentation = presentation,
            failures = failures
        )
        val stateSync = MatchStateSync(matchState = matchState, signs = signs)
        val progression = MatchProgressionService(
            registry = registry,
            sync = stateSync,
            stats = stats,
            kit = equipment,
            players = playerPort,
            scheduler = PaperScheduler(this),
            presentation = presentation,
            recovery = recovery,
            failures = failures
        )
        val service = ArenaApplicationService(
            registry = registry,
            players = playerPort,
            recovery = recovery,
            failures = failures,
            progression = progression,
            sync = stateSync
        )
        lifecycle = ArenaLifecycleService(
            registry = registry,
            arenas = arenaRepository,
            sync = stateSync,
            recovery = recovery,
            progression = progression,
            failures = failures
        )
        val admin = ArenaAdministrationService(
            registry = registry,
            arenas = arenaRepository,
            signs = signRepository,
            kit = equipment,
            progression = progression
        )
        val lobby = LobbyService(lobby = lobbyRepository)
        val statsService = PlayerStatsService(stats = stats)
        lifecycle.load()
        this.service = service

        val executor = OneVsOneCommand(
            service = service,
            admin = admin,
            statsService = statsService,
            signs = signs,
            lobby = lobby,
            players = playerPort,
            failures = failures,
            messenger = messenger
        )
        val command = getCommand("1vs1") ?: error("1vs1 command missing from plugin.yml")
        @Suppress("UsePropertyAccessSyntax") // the setter takes @Nullable, so executor stays a val-style property access
        command.setExecutor(executor)
        command.tabCompleter = executor
        server.pluginManager.registerEvents(ArenaMatchListener(service, lookup, messenger), this)
        server.pluginManager.registerEvents(ArenaGuardListener(service), this)
        server.pluginManager.registerEvents(ArenaTeleportListener(service, lookup), this)
        server.pluginManager.registerEvents(ArenaSignListener(service, signs, messenger), this)
    }

    override fun onDisable() {
        if (::lifecycle.isInitialized) lifecycle.shutdown()
    }
}
