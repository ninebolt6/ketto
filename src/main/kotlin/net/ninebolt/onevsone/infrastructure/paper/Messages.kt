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
 * lang/messages_<lang>.yml 上の MiniMessage テンプレートを描画するメッセージ基盤。
 * send/broadcast は宛先ロケール(language=auto 時はクライアント設定)で描画し、
 * 看板・アイテム名・スコアボード等の共有面は render(サーバー言語)を使う。
 */
class Messages private constructor(
    private val bundles: Map<String, Map<String, String>>,
    private val defaultLang: String,
    private val chatLang: String?,
    private val logger: Logger
) {
    private val mini = MiniMessage.miniMessage()
    private val warnedMissing = mutableSetOf<String>()

    /** 共有面・コンソール向けの言語。固定モードではその言語、auto では既定言語。 */
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

    /** msg を描画する。lang 省略時はサーバー言語(看板・アイテム等の共有面用)。 */
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

    /** プレイヤーは各自のロケール、コンソールはサーバー言語で送る。 */
    fun broadcast(server: Server, msg: Msg) {
        server.onlinePlayers.forEach { send(it, msg) }
        server.consoleSender.sendMessage(render(Msg(MessageKeys.PREFIX)).append(render(msg)))
    }

    // ---- Msg ファクトリ(描画は行わずキーと引数だけを持つ) ----

    val usageRoot = Msg(MessageKeys.USAGE_ROOT)
    val usageArena = Msg(MessageKeys.USAGE_ARENA)
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
    fun usageSetSpawn(number: Int) = Msg(MessageKeys.USAGE_SET_SPAWN, Msg.Str("n", "$number"))
    val usageEnable = Msg(MessageKeys.USAGE_ENABLE)
    val usageDisable = Msg(MessageKeys.USAGE_DISABLE)
    val usageSetInv = Msg(MessageKeys.USAGE_SET_INV)
    val usageSetSign = Msg(MessageKeys.USAGE_SET_SIGN)
    val usageRemoveSign = Msg(MessageKeys.USAGE_REMOVE_SIGN)
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
        /** jar に同梱する既定言語。ユーザー追加言語は dataFolder/lang/messages_<lang>.yml で読む。 */
        private val BUNDLED_LANGS = listOf("ja", "en")

        /**
         * 同梱言語を基底に、langDir/messages_*.yml を言語別に上書きマージして読み込む。
         * language が "auto" なら宛先ロケール描画、それ以外なら全宛先をその言語に固定する。
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

        /** ネストした YAML を a.b.c キーのテンプレートマップへ平坦化する。 */
        private fun flatten(config: YamlConfiguration): Map<String, String> =
            config.getKeys(true).mapNotNull { key -> config.getString(key)?.let { key to it } }.toMap()
    }
}
