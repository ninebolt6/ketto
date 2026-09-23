package net.ninebolt.onevsone.infrastructure.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.PlayerStats
import org.bukkit.Server
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.io.File
import java.util.Locale
import java.util.logging.Logger

/**
 * Message infrastructure rendering MiniMessage templates from
 * lang/messages_<lang>.yml. send/broadcast render in the recipient's locale
 * (the client setting when language=auto); shared surfaces such as signs, item
 * names, and the scoreboard use render (the server language).
 */
class Messages private constructor(
    private val bundles: Map<String, Map<String, String>>,
    private val defaultLang: String,
    private val chatLang: String?,
    private val logger: Logger
) {
    private val mini = MiniMessage.miniMessage()
    private val warnedMissing = mutableSetOf<String>()

    /** Language for shared surfaces and console. The fixed language in fixed mode, the default language in auto. */
    private val serverLang = chatLang ?: defaultLang

    private fun localeOf(sender: CommandSender): String {
        if (chatLang != null) return chatLang
        val locale = (sender as? Player)?.let { runCatching { it.locale() }.getOrNull() } ?: return defaultLang
        val langTag = locale.toString().lowercase(Locale.ROOT)
        return when {
            langTag in bundles -> langTag
            locale.language in bundles -> locale.language
            else -> defaultLang
        }
    }

    private fun template(lang: String, key: String): String =
        bundles[lang]?.get(key) ?: bundles[defaultLang]?.get(key) ?: run {
            if (warnedMissing.add("$lang:$key")) {
                logger.warning("Missing message key '$key' for lang '$lang'")
            }
            key
        }

    /** Renders msg. When lang is omitted, uses the server language (for shared surfaces like signs and items). */
    fun render(msg: Msg, lang: String = serverLang): Component =
        mini.deserialize(template(lang, msg.key), *msg.args.map { arg ->
            when (arg) {
                is Msg.Str -> Placeholder.unparsed(arg.name, arg.value)
                is Msg.Nested -> Placeholder.component(arg.name, render(arg.msg, lang))
            }
        }.toTypedArray<TagResolver>())

    fun send(sender: CommandSender, msg: Msg) {
        val lang = localeOf(sender)
        sender.sendMessage(render(Msg(MessageKeys.PREFIX), lang).append(render(msg, lang)))
    }

    /** Players get their own locale; the console gets the server language. */
    fun broadcast(server: Server, msg: Msg) {
        server.onlinePlayers.forEach { send(it, msg) }
        server.consoleSender.sendMessage(render(Msg(MessageKeys.PREFIX)).append(render(msg)))
    }

    // ---- Msg factories (hold only keys and args; no rendering) ----

    val usageRoot = Msg(MessageKeys.USAGE_ROOT)
    val usageArena = Msg(MessageKeys.USAGE_ARENA)
    val usageArenaOps = Msg(MessageKeys.USAGE_ARENA_OPS)
    val usageLobby = Msg(MessageKeys.USAGE_LOBBY)
    val noPermission = Msg(MessageKeys.COMMAND_NO_PERMISSION)
    val playerOnly = Msg(MessageKeys.COMMAND_PLAYER_ONLY)
    val lobbySet = Msg(MessageKeys.LOBBY_SET)
    fun arenaHeader(name: String) = Msg(MessageKeys.ARENA_INFO_HEADER, Msg.Str("name", name))
    fun arenaState(state: ArenaState) =
        Msg(MessageKeys.ARENA_INFO_STATE, Msg.Nested("display", stateDisplay(state)))
    fun versus(name1: String, name2: String) =
        Msg(MessageKeys.ARENA_INFO_VERSUS, Msg.Str("name1", name1), Msg.Str("name2", name2))
    fun winCount(a: Int, b: Int) =
        Msg(MessageKeys.ARENA_INFO_WIN_COUNT, Msg.Str("wins1", "$a"), Msg.Str("wins2", "$b"))
    val arenaExists = Msg(MessageKeys.ARENA_EXISTS)
    fun created(name: String) = Msg(MessageKeys.ARENA_CREATED, Msg.Str("name", name))
    val noArena = Msg(MessageKeys.ARENA_NOT_FOUND)
    fun removed(name: String) = Msg(MessageKeys.ARENA_REMOVED, Msg.Str("name", name))
    fun spawnSet(name: String, slot: Int) =
        Msg(MessageKeys.ARENA_SPAWN_SET, Msg.Str("name", name), Msg.Str("slot", "$slot"))
    fun enabled(name: String) = Msg(MessageKeys.ARENA_ENABLED, Msg.Str("name", name))
    val alreadyEnabled = Msg(MessageKeys.ARENA_ALREADY_ENABLED)
    fun disabled(name: String) = Msg(MessageKeys.ARENA_DISABLED, Msg.Str("name", name))
    val alreadyDisabled = Msg(MessageKeys.ARENA_ALREADY_DISABLED)
    fun inventorySet(name: String) = Msg(MessageKeys.ARENA_INVENTORY_SET, Msg.Str("name", name))
    val lookAtSign = Msg(MessageKeys.SIGN_LOOK_AT)
    val signTaken = Msg(MessageKeys.SIGN_TAKEN)
    val usageCreate = Msg(MessageKeys.USAGE_CREATE)
    val usageRemove = Msg(MessageKeys.USAGE_REMOVE)
    val usageSpawn = Msg(MessageKeys.USAGE_SPAWN)
    val usageEnable = Msg(MessageKeys.USAGE_ENABLE)
    val usageDisable = Msg(MessageKeys.USAGE_DISABLE)
    val usageKit = Msg(MessageKeys.USAGE_KIT)
    val usageSign = Msg(MessageKeys.USAGE_SIGN)
    val usageSignSet = Msg(MessageKeys.USAGE_SIGN_SET)
    val usageSignRemove = Msg(MessageKeys.USAGE_SIGN_REMOVE)
    fun signRemoved(name: String) = Msg(MessageKeys.SIGN_REMOVED, Msg.Str("name", name))
    val signNotRegistered = Msg(MessageKeys.SIGN_NOT_REGISTERED)
    fun joined(name: String) = Msg(MessageKeys.MATCH_JOINED, Msg.Str("name", name))
    val waitOneMore = Msg(MessageKeys.MATCH_WAIT_ONE_MORE)
    val notEnabled = Msg(MessageKeys.ARENA_NOT_ENABLED)
    val alreadyJoined = Msg(MessageKeys.MATCH_ALREADY_JOINED)
    val arenaInGame = Msg(MessageKeys.MATCH_IN_GAME)
    val leftArena = Msg(MessageKeys.MATCH_LEFT)
    val cannotLeave = Msg(MessageKeys.MATCH_CANNOT_LEAVE)
    val notJoined = Msg(MessageKeys.MATCH_NOT_JOINED)
    val commandBlocked = Msg(MessageKeys.COMMAND_BLOCKED)
    fun teleportIn(n: Int) = Msg(MessageKeys.MATCH_TELEPORT_IN, Msg.Str("n", "$n"))
    val gameStart = Msg(MessageKeys.MATCH_GAME_START)
    fun startIn(n: Int) = Msg(MessageKeys.MATCH_START_IN, Msg.Str("n", "$n"))
    val roundStart = Msg(MessageKeys.MATCH_ROUND_START)
    fun roundWinner(round: Int, name: String) =
        Msg(MessageKeys.MATCH_ROUND_WINNER, Msg.Str("round", "$round"), Msg.Str("name", name))
    fun champion(arena: String, name: String) =
        Msg(MessageKeys.MATCH_CHAMPION, Msg.Str("arena", arena), Msg.Str("name", name))
    fun statWin(win: Int) = Msg(MessageKeys.STATS_WIN, Msg.Str("wins", "$win"))
    fun statLose(lose: Int) = Msg(MessageKeys.STATS_LOSE, Msg.Str("losses", "$lose"))
    fun statRatio(stats: PlayerStats) =
        Msg(MessageKeys.STATS_RATIO, Msg.Str("ratio", "%.2f".format(Locale.ROOT, stats.ratio)))
    val noStats = Msg(MessageKeys.STATS_NONE)
    val statsCooldown = Msg(MessageKeys.STATS_COOLDOWN)

    val signTitle = Msg(MessageKeys.SIGN_TITLE)
    fun signArena(name: String) = Msg(MessageKeys.SIGN_ARENA, Msg.Str("name", name))
    val signJoin = Msg(MessageKeys.SIGN_JOIN)
    val signCannotJoin = Msg(MessageKeys.SIGN_CANNOT_JOIN)

    fun scoreboardTitle(arena: String) = Msg(MessageKeys.SCOREBOARD_TITLE, Msg.Str("arena", arena))
    fun scoreboardEntry(name: String) = Msg(MessageKeys.SCOREBOARD_ENTRY, Msg.Str("name", name))

    fun stateDisplay(state: ArenaState): Msg = Msg(when (state) {
        ArenaState.WAITING -> MessageKeys.STATE_WAITING
        ArenaState.ONEMORE -> MessageKeys.STATE_ONEMORE
        ArenaState.COUNTDOWN -> MessageKeys.STATE_COUNTDOWN
        ArenaState.ROUNDCOUNTDOWN, ArenaState.INGAME -> MessageKeys.STATE_INGAME
    })

    companion object {
        /** Default languages bundled in the jar. User-added languages load from dataFolder/lang/messages_<lang>.yml. */
        private val BUNDLED_LANGS = listOf("ja", "en")

        /**
         * Loads langDir/messages_*.yml merged per language over the bundled
         * languages. "auto" renders per recipient locale; anything else pins
         * all recipients to that language.
         */
        fun load(langDir: File, defaultLang: String, language: String, logger: Logger): Messages {
            val bundled = BUNDLED_LANGS.mapNotNull { lang ->
                val yaml = Messages::class.java.getResourceAsStream("/lang/messages_$lang.yml")
                    ?.bufferedReader()?.use { YamlConfiguration.loadConfiguration(it) }
                    ?: return@mapNotNull null
                lang to flatten(yaml)
            }.toMap()
            val overrides = langDir.listFiles { f -> f.name.matches(Regex("messages_.+\\.yml")) }
                ?.associate { f ->
                    f.name.removePrefix("messages_").removeSuffix(".yml").lowercase(Locale.ROOT) to
                        flatten(YamlConfiguration.loadConfiguration(f))
                } ?: emptyMap()
            val bundles = (bundled.keys + overrides.keys)
                .associateWith { (bundled[it] ?: emptyMap()) + (overrides[it] ?: emptyMap()) }
            (bundles[defaultLang]?.keys ?: emptySet()).forEach { key ->
                bundles.filterKeys { it != defaultLang }
                    .filterValues { key !in it }
                    .keys.forEach { lang -> logger.warning("messages_$lang.yml is missing key '$key' (falls back to '$defaultLang')") }
            }
            return Messages(bundles, defaultLang, if (language.equals("auto", ignoreCase = true)) null else language, logger)
        }

        /** Flattens nested YAML into a map of a.b.c keys to templates. */
        private fun flatten(config: YamlConfiguration): Map<String, String> =
            config.getKeys(true).mapNotNull { key -> config.getString(key)?.let { key to it } }.toMap()
    }
}
