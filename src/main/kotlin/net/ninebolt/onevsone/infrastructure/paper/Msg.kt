package net.ninebolt.onevsone.infrastructure.paper

/**
 * 遅延描画メッセージ。言語ファイル(lang/messages_<lang>.yml)のキーと
 * プレースホルダ引数を保持し、Messages.render で宛先ロケールに描画される。
 * 引数は Str(プレーンテキスト。`<` 等はタグ化しない)か Nested(同ロケールで
 * 描画される別 Msg)のいずれか。
 */
class Msg internal constructor(val key: String, vararg val args: Arg) {

    sealed interface Arg {
        val name: String
    }

    data class Str(override val name: String, val value: String) : Arg

    data class Nested(override val name: String, val msg: Msg) : Arg
}

/** 言語ファイルのキー一覧。グループの共通プレフィックスは private const で共有する。 */
object MessageKeys {
    const val PREFIX = "prefix"

    private const val USAGE = "usage"
    const val USAGE_ROOT = "${USAGE}.root"
    const val USAGE_ARENA = "${USAGE}.arena"
    const val USAGE_CREATE = "${USAGE}.create"
    const val USAGE_REMOVE = "${USAGE}.remove"
    const val USAGE_SET_SPAWN = "${USAGE}.set-spawn"
    const val USAGE_ENABLE = "${USAGE}.enable"
    const val USAGE_DISABLE = "${USAGE}.disable"
    const val USAGE_SET_INV = "${USAGE}.set-inv"
    const val USAGE_SET_SIGN = "${USAGE}.set-sign"
    const val USAGE_REMOVE_SIGN = "${USAGE}.remove-sign"

    private const val COMMAND = "command"
    const val COMMAND_NO_PERMISSION = "${COMMAND}.no-permission"
    const val COMMAND_PLAYER_ONLY = "${COMMAND}.player-only"
    const val COMMAND_BLOCKED = "${COMMAND}.blocked"

    const val LOBBY_SET = "lobby.set"

    private const val ARENA = "arena"
    const val ARENA_EXISTS = "${ARENA}.exists"
    const val ARENA_NOT_FOUND = "${ARENA}.not-found"
    const val ARENA_CREATED = "${ARENA}.created"
    const val ARENA_REMOVED = "${ARENA}.removed"
    const val ARENA_SPAWN_SET = "${ARENA}.spawn-set"
    const val ARENA_ENABLED = "${ARENA}.enabled"
    const val ARENA_ALREADY_ENABLED = "${ARENA}.already-enabled"
    const val ARENA_DISABLED = "${ARENA}.disabled"
    const val ARENA_ALREADY_DISABLED = "${ARENA}.already-disabled"
    const val ARENA_INVENTORY_SET = "${ARENA}.inventory-set"
    const val ARENA_NOT_ENABLED = "${ARENA}.not-enabled"

    private const val ARENA_INFO = "arena.info"
    const val ARENA_INFO_HEADER = "${ARENA_INFO}.header"
    const val ARENA_INFO_STATE = "${ARENA_INFO}.state"
    const val ARENA_INFO_VERSUS = "${ARENA_INFO}.versus"
    const val ARENA_INFO_WIN_COUNT = "${ARENA_INFO}.wins"

    private const val MATCH = "match"
    const val MATCH_JOINED = "${MATCH}.joined"
    const val MATCH_WAIT_ONE_MORE = "${MATCH}.wait-one-more"
    const val MATCH_ALREADY_JOINED = "${MATCH}.already-joined"
    const val MATCH_IN_GAME = "${MATCH}.in-game"
    const val MATCH_LEFT = "${MATCH}.left"
    const val MATCH_CANNOT_LEAVE = "${MATCH}.cannot-leave"
    const val MATCH_NOT_JOINED = "${MATCH}.not-joined"
    const val MATCH_TELEPORT_IN = "${MATCH}.teleport-in"
    const val MATCH_GAME_START = "${MATCH}.game-start"
    const val MATCH_START_IN = "${MATCH}.start-in"
    const val MATCH_ROUND_START = "${MATCH}.round-start"
    const val MATCH_ROUND_WINNER = "${MATCH}.round-winner"
    const val MATCH_CHAMPION = "${MATCH}.champion"

    private const val STATS = "stats"
    const val STATS_WIN = "${STATS}.win"
    const val STATS_LOSE = "${STATS}.lose"
    const val STATS_RATIO = "${STATS}.ratio"
    const val STATS_NONE = "${STATS}.none"

    private const val SIGN = "sign"
    const val SIGN_LOOK_AT = "${SIGN}.look-at"
    const val SIGN_TAKEN = "${SIGN}.taken"
    const val SIGN_REMOVED = "${SIGN}.removed"
    const val SIGN_NOT_REGISTERED = "${SIGN}.not-registered"
    const val SIGN_TITLE = "${SIGN}.title"
    const val SIGN_ARENA = "${SIGN}.arena"
    const val SIGN_JOIN = "${SIGN}.join"
    const val SIGN_CANNOT_JOIN = "${SIGN}.cannot-join"

    private const val STATE = "state"
    const val STATE_WAITING = "${STATE}.waiting"
    const val STATE_ONEMORE = "${STATE}.onemore"
    const val STATE_COUNTDOWN = "${STATE}.countdown"
    const val STATE_INGAME = "${STATE}.ingame"

    private const val SCOREBOARD = "scoreboard"
    const val SCOREBOARD_TITLE = "${SCOREBOARD}.title"
    const val SCOREBOARD_ENTRY = "${SCOREBOARD}.entry"
}
