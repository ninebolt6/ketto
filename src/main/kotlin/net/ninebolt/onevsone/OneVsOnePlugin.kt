package net.ninebolt.onevsone

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.application.ArenaApplicationService
import net.ninebolt.onevsone.application.ArenaRegistry
import net.ninebolt.onevsone.application.PlayerRecoveryService
import net.ninebolt.onevsone.infrastructure.paper.ArenaListener
import net.ninebolt.onevsone.infrastructure.paper.ArenaSignListener
import net.ninebolt.onevsone.infrastructure.paper.Messages
import net.ninebolt.onevsone.infrastructure.paper.command.OneVsOneCommand
import net.ninebolt.onevsone.infrastructure.paper.PaperEquipmentAdapter
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
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

/**
 * composition root。設定値を読み、実装を手動で生成・注入し、
 * イベントとコマンドを登録する。main FQCN は維持。
 */
class OneVsOnePlugin : JavaPlugin() {

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
        saveResource("lang/messages_ja.yml", false)
        saveResource("lang/messages_en.yml", false)
        val messages = Messages.load(
            langDir = File(dataFolder, "lang"),
            defaultLang = config.getString("default-language") ?: "ja",
            language = config.getString("language") ?: "auto",
            logger = logger
        )

        val lookup = PaperPlayerLookup(server)
        val playerPort = PaperPlayerAdapter(lookup, server, failures)
        val equipment = PaperEquipmentAdapter(store, lookup, messages)
        val scheduler = PaperScheduler(this)
        val presentation = PaperMatchPresentation(server, messages, signRepository, failures)

        val registry = ArenaRegistry()
        val recovery = PlayerRecoveryService(equipment, playerPort, lobbyRepository, presentation, failures)
        val service = ArenaApplicationService(
            registry = registry,
            arenas = arenaRepository,
            matchState = matchState,
            stats = stats,
            backups = equipment,
            kit = equipment,
            players = playerPort,
            scheduler = scheduler,
            presentation = presentation,
            recovery = recovery,
            failures = failures,
            requiredWins = requiredWins
        )
        val admin = ArenaAdministrationService(registry, arenaRepository, signRepository, lobbyRepository, equipment, presentation, service)
        this.service = service
        service.load()

        val executor = OneVsOneCommand(this, service, admin, messages)
        val command = getCommand("1vs1")
        @Suppress("UsePropertyAccessSyntax") // setter が @Nullable 引数のため executor は val 扱い
        command?.setExecutor(executor)
        command?.tabCompleter = executor
        server.pluginManager.registerEvents(ArenaListener(service, lookup, messages), this)
        server.pluginManager.registerEvents(ArenaSignListener(service, admin, messages), this)
    }

    override fun onDisable() {
        service?.shutdown()
    }
}
