package net.ninebolt.onevsone

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.application.ArenaApplicationService
import net.ninebolt.onevsone.application.ArenaRegistry
import net.ninebolt.onevsone.application.MatchProgressionService
import net.ninebolt.onevsone.application.MatchStateSync
import net.ninebolt.onevsone.application.PlayerRecoveryService
import net.ninebolt.onevsone.infrastructure.paper.ArenaGuardListener
import net.ninebolt.onevsone.infrastructure.paper.ArenaMatchListener
import net.ninebolt.onevsone.infrastructure.paper.ArenaSignListener
import net.ninebolt.onevsone.infrastructure.paper.ArenaTeleportListener
import net.ninebolt.onevsone.infrastructure.paper.LanguageFiles
import net.ninebolt.onevsone.infrastructure.paper.Messenger
import net.ninebolt.onevsone.infrastructure.paper.command.OneVsOneCommand
import net.ninebolt.onevsone.infrastructure.paper.PaperEquipmentAdapter
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
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

/**
 * Composition root. Reads config values, manually instantiates and injects the
 * implementations, and registers events and commands. The main FQCN is kept.
 */
// open is required because MockBukkit generates a proxy subclass at load time
open class OneVsOnePlugin : JavaPlugin() {

    var service: ArenaApplicationService? = null
        private set

    override fun onEnable() {
        saveDefaultConfig()
        val configured = config.getInt("required-wins", 3)
        if (configured < 1) {
            logger.warning("required-wins must be >= 1 (was $configured); using 1")
        }
        val requiredWins = configured.coerceAtLeast(1)

        val failures = PluginFailureReporter { logger }
        val store = YamlStore(dataFolder, logger)
        val arenaRepository = YamlArenaRepository(store)
        val lobbyRepository = YamlLobbyRepository(store)
        val signRepository = YamlSignRepository(store)
        val matchState = YamlMatchStateRepository(store)
        val stats = YamlPlayerStatsRepository(store)
        LanguageFiles.syncBundled(dataFolder, logger) {
            saveResource(it, false)
        }
        val messenger = Messenger.load(
            messagesDir = File(dataFolder, "messages"),
            fallbackLang = config.getString("default-language") ?: "en",
            language = config.getString("language") ?: "auto",
            logger = logger
        )

        val lookup = PaperPlayerLookup(server)
        val playerPort = PaperPlayerAdapter(lookup, server, this, failures)
        val equipment = PaperEquipmentAdapter(YamlBackupStore(store), YamlKitStore(store), lookup)
        val scheduler = PaperScheduler(this)
        val presentation = PaperMatchPresentation(server, messenger, signRepository, failures)

        val registry = ArenaRegistry(requiredWins)
        val recovery = PlayerRecoveryService(equipment, playerPort, lobbyRepository, presentation, failures, server.onlineMode)
        val stateSync = MatchStateSync(matchState, presentation)
        val progression = MatchProgressionService(
            registry = registry,
            sync = stateSync,
            stats = stats,
            backups = equipment,
            kit = equipment,
            players = playerPort,
            scheduler = scheduler,
            presentation = presentation,
            recovery = recovery,
            failures = failures
        )
        val service = ArenaApplicationService(
            registry = registry,
            arenas = arenaRepository,
            matchState = matchState,
            stats = stats,
            players = playerPort,
            presentation = presentation,
            recovery = recovery,
            failures = failures,
            progression = progression,
            sync = stateSync
        )
        val admin = ArenaAdministrationService(registry, arenaRepository, signRepository, lobbyRepository, equipment, presentation, progression)
        this.service = service
        service.load()

        val executor = OneVsOneCommand(service, admin, playerPort, failures, messenger)
        val command = getCommand("1vs1")
        @Suppress("UsePropertyAccessSyntax") // the setter takes @Nullable, so executor stays a val-style property access
        command?.setExecutor(executor)
        command?.tabCompleter = executor
        server.pluginManager.registerEvents(ArenaMatchListener(service, lookup, messenger), this)
        server.pluginManager.registerEvents(ArenaGuardListener(service), this)
        server.pluginManager.registerEvents(ArenaTeleportListener(service, lookup), this)
        server.pluginManager.registerEvents(ArenaSignListener(service, admin, messenger), this)
    }

    override fun onDisable() {
        service?.shutdown()
    }
}
