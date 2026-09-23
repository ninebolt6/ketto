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
 * messages/<lang>.yaml. send/broadcast render in the recipient's locale
 * (the client setting when language=auto); shared surfaces such as signs, item
 * names, and the scoreboard use render (the server language).
 */
class Messages private constructor(
    private val bundles: Map<String, Map<String, String>>,
    private val fallbackLang: String,
    private val chatLang: String?,
    private val logger: Logger
) {
    private val mini = MiniMessage.miniMessage()
    private val warnedMissing = mutableSetOf<String>()

    /** Language for shared surfaces and console. The fixed language in fixed mode, the fallback language in auto. */
    private val serverLang = chatLang ?: fallbackLang

    private fun localeOf(sender: CommandSender): String {
        if (chatLang != null) return chatLang
        val locale = (sender as? Player)?.let { runCatching { it.locale() }.getOrNull() } ?: return fallbackLang
        val langTag = locale.toString().lowercase(Locale.ROOT)
        return when {
            langTag in bundles -> langTag
            locale.language in bundles -> locale.language
            else -> fallbackLang
        }
    }

    private fun template(lang: String, key: String): String =
        bundles[lang]?.get(key) ?: bundles[fallbackLang]?.get(key) ?: run {
            if (warnedMissing.add("$lang:$key")) {
                logger.warning("Missing message key '$key' for lang '$lang'")
            }
            key
        }

    /** Renders msg. When lang is omitted, uses the server language (for shared surfaces like signs and items). */
    fun render(msg: Msg, lang: String = serverLang): Component =
        mini.deserialize(template(lang, msg.key.name), *msg.args.map { arg ->
            when (arg) {
                is Msg.Str -> Placeholder.unparsed(arg.name, arg.value)
                is Msg.Nested -> Placeholder.component(arg.name, render(arg.msg, lang))
            }
        }.toTypedArray<TagResolver>())

    fun send(sender: CommandSender, msg: Msg) {
        val lang = localeOf(sender)
        sender.sendMessage(render(Msg(MessageKey.PREFIX), lang).append(render(msg, lang)))
    }

    /** Players get their own locale; the console gets the server language. */
    fun broadcast(server: Server, msg: Msg) {
        server.onlinePlayers.forEach { send(it, msg) }
        server.consoleSender.sendMessage(render(Msg(MessageKey.PREFIX)).append(render(msg)))
    }

    // ---- Msg factories (hold only keys and args; no rendering) ----

    val usageRoot = Msg(MessageKey.USAGE_ROOT)
    val usageArena = Msg(MessageKey.USAGE_ARENA)
    val usageArenaOps = Msg(MessageKey.USAGE_ARENA_OPS)
    val usageLobby = Msg(MessageKey.USAGE_LOBBY)
    val noPermission = Msg(MessageKey.COMMAND_NO_PERMISSION)
    val playerOnly = Msg(MessageKey.COMMAND_PLAYER_ONLY)
    val lobbySet = Msg(MessageKey.LOBBY_SET)
    fun arenaHeader(name: String) = Msg(MessageKey.ARENA_INFO_HEADER, Msg.Str("name", name))
    fun arenaState(state: ArenaState) =
        Msg(MessageKey.ARENA_INFO_STATE, Msg.Nested("display", stateDisplay(state)))
    fun versus(name1: String, name2: String) =
        Msg(MessageKey.ARENA_INFO_VERSUS, Msg.Str("name1", name1), Msg.Str("name2", name2))
    fun winCount(a: Int, b: Int) =
        Msg(MessageKey.ARENA_INFO_WIN_COUNT, Msg.Str("wins1", "$a"), Msg.Str("wins2", "$b"))
    val arenaExists = Msg(MessageKey.ARENA_EXISTS)
    fun created(name: String) = Msg(MessageKey.ARENA_CREATED, Msg.Str("name", name))
    val noArena = Msg(MessageKey.ARENA_NOT_FOUND)
    fun removed(name: String) = Msg(MessageKey.ARENA_REMOVED, Msg.Str("name", name))
    fun spawnSet(name: String, slot: Int) =
        Msg(MessageKey.ARENA_SPAWN_SET, Msg.Str("name", name), Msg.Str("slot", "$slot"))
    fun enabled(name: String) = Msg(MessageKey.ARENA_ENABLED, Msg.Str("name", name))
    val alreadyEnabled = Msg(MessageKey.ARENA_ALREADY_ENABLED)
    fun disabled(name: String) = Msg(MessageKey.ARENA_DISABLED, Msg.Str("name", name))
    val alreadyDisabled = Msg(MessageKey.ARENA_ALREADY_DISABLED)
    fun inventorySet(name: String) = Msg(MessageKey.ARENA_INVENTORY_SET, Msg.Str("name", name))
    val lookAtSign = Msg(MessageKey.SIGN_LOOK_AT)
    val signTaken = Msg(MessageKey.SIGN_TAKEN)
    val usageCreate = Msg(MessageKey.USAGE_CREATE)
    val usageRemove = Msg(MessageKey.USAGE_REMOVE)
    val usageSpawn = Msg(MessageKey.USAGE_SPAWN)
    val usageEnable = Msg(MessageKey.USAGE_ENABLE)
    val usageDisable = Msg(MessageKey.USAGE_DISABLE)
    val usageKit = Msg(MessageKey.USAGE_KIT)
    val usageSign = Msg(MessageKey.USAGE_SIGN)
    val usageSignSet = Msg(MessageKey.USAGE_SIGN_SET)
    val usageSignRemove = Msg(MessageKey.USAGE_SIGN_REMOVE)
    fun signRemoved(name: String) = Msg(MessageKey.SIGN_REMOVED, Msg.Str("name", name))
    val signNotRegistered = Msg(MessageKey.SIGN_NOT_REGISTERED)
    fun joined(name: String) = Msg(MessageKey.MATCH_JOINED, Msg.Str("name", name))
    val waitOneMore = Msg(MessageKey.MATCH_WAIT_ONE_MORE)
    val notEnabled = Msg(MessageKey.ARENA_NOT_ENABLED)
    val alreadyJoined = Msg(MessageKey.MATCH_ALREADY_JOINED)
    val arenaInGame = Msg(MessageKey.MATCH_IN_GAME)
    val leftArena = Msg(MessageKey.MATCH_LEFT)
    val cannotLeave = Msg(MessageKey.MATCH_CANNOT_LEAVE)
    val notJoined = Msg(MessageKey.MATCH_NOT_JOINED)
    val commandBlocked = Msg(MessageKey.COMMAND_BLOCKED)
    fun teleportIn(n: Int) = Msg(MessageKey.MATCH_TELEPORT_IN, Msg.Str("n", "$n"))
    val gameStart = Msg(MessageKey.MATCH_GAME_START)
    fun startIn(n: Int) = Msg(MessageKey.MATCH_START_IN, Msg.Str("n", "$n"))
    val roundStart = Msg(MessageKey.MATCH_ROUND_START)
    fun roundWinner(round: Int, name: String) =
        Msg(MessageKey.MATCH_ROUND_WINNER, Msg.Str("round", "$round"), Msg.Str("name", name))
    fun champion(arena: String, name: String) =
        Msg(MessageKey.MATCH_CHAMPION, Msg.Str("arena", arena), Msg.Str("name", name))
    fun statWin(win: Int) = Msg(MessageKey.STATS_WIN, Msg.Str("wins", "$win"))
    fun statLose(lose: Int) = Msg(MessageKey.STATS_LOSE, Msg.Str("losses", "$lose"))
    fun statRatio(stats: PlayerStats) =
        Msg(MessageKey.STATS_RATIO, Msg.Str("ratio", "%.2f".format(Locale.ROOT, stats.ratio)))
    val noStats = Msg(MessageKey.STATS_NONE)
    val statsCooldown = Msg(MessageKey.STATS_COOLDOWN)

    val signTitle = Msg(MessageKey.SIGN_TITLE)
    fun signArena(name: String) = Msg(MessageKey.SIGN_ARENA, Msg.Str("name", name))
    val signJoin = Msg(MessageKey.SIGN_JOIN)
    val signCannotJoin = Msg(MessageKey.SIGN_CANNOT_JOIN)

    fun scoreboardTitle(arena: String) = Msg(MessageKey.SCOREBOARD_TITLE, Msg.Str("arena", arena))
    fun scoreboardEntry(name: String) = Msg(MessageKey.SCOREBOARD_ENTRY, Msg.Str("name", name))

    fun stateDisplay(state: ArenaState): Msg = Msg(when (state) {
        ArenaState.WAITING -> MessageKey.STATE_WAITING
        ArenaState.ONEMORE -> MessageKey.STATE_ONEMORE
        ArenaState.COUNTDOWN -> MessageKey.STATE_COUNTDOWN
        ArenaState.ROUNDCOUNTDOWN, ArenaState.INGAME -> MessageKey.STATE_INGAME
    })

    companion object {
        /** Default languages bundled in the jar. User-added languages load from dataFolder/messages/<lang>.yaml. */
        private val BUNDLED_LANGS = listOf("ja", "en")

        /**
         * Loads messagesDir/<lang>.{yaml,yml} merged per language over the
         * bundled languages; when both extensions exist for a language, .yaml
         * wins. "auto" renders per recipient locale; anything else pins all
         * recipients to that language.
         */
        fun load(messagesDir: File, fallbackLang: String, language: String, logger: Logger): Messages {
            val bundled = BUNDLED_LANGS.mapNotNull { lang ->
                val yaml = Messages::class.java.getResourceAsStream("/messages/$lang.yaml")
                    ?.bufferedReader()?.use { YamlConfiguration.loadConfiguration(it) }
                    ?: return@mapNotNull null
                lang to flatten(yaml)
            }.toMap()
            val overrides = messagesDir.listFiles { f -> f.name.matches(Regex(".+\\.ya?ml")) }
                ?.groupBy { it.nameWithoutExtension.lowercase(Locale.ROOT) }
                ?.mapValues { (_, files) ->
                    flatten(YamlConfiguration.loadConfiguration(
                        files.firstOrNull { it.extension == "yaml" } ?: files.first()))
                } ?: emptyMap()
            val bundles = (bundled.keys + overrides.keys)
                .associateWith { (bundled[it] ?: emptyMap()) + (overrides[it] ?: emptyMap()) }
            (bundles[fallbackLang]?.keys ?: emptySet()).forEach { key ->
                bundles.filterKeys { it != fallbackLang }
                    .filterValues { key !in it }
                    .keys.forEach { lang -> logger.warning("language '$lang' is missing key '$key' (falls back to '$fallbackLang')") }
            }
            return Messages(bundles, fallbackLang, if (language.equals("auto", ignoreCase = true)) null else language, logger)
        }

        /** Flattens nested YAML into a map of a.b.c keys to templates. */
        private fun flatten(config: YamlConfiguration): Map<String, String> =
            config.getKeys(true).mapNotNull { key -> config.getString(key)?.let { key to it } }.toMap()
    }
}
