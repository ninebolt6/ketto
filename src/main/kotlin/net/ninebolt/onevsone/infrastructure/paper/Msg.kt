package net.ninebolt.onevsone.infrastructure.paper

/**
 * Lazily rendered message. Holds a key from a language file
 * (messages/<lang>.yaml) plus placeholder arguments, rendered into the
 * recipient's locale by Messages.render. Arguments are either Str (plain text;
 * `<` etc. are never parsed as tags) or Nested (another Msg rendered in the
 * same locale).
 */
class Msg internal constructor(val key: MessageKey, vararg val args: Arg) {

    sealed interface Arg {
        val name: String
    }

    data class Str(override val name: String, val value: String) : Arg

    data class Nested(override val name: String, val msg: Msg) : Arg
}

/** All keys in the language files. Each entry's name is the YAML key. */
enum class MessageKey {
    PREFIX,

    USAGE_ROOT,
    USAGE_ARENA,
    USAGE_ARENA_OPS,
    USAGE_LOBBY,
    USAGE_CREATE,
    USAGE_REMOVE,
    USAGE_SPAWN,
    USAGE_ENABLE,
    USAGE_DISABLE,
    USAGE_KIT,
    USAGE_SIGN,
    USAGE_SIGN_SET,
    USAGE_SIGN_REMOVE,

    COMMAND_NO_PERMISSION,
    COMMAND_PLAYER_ONLY,
    COMMAND_BLOCKED,

    LOBBY_SET,

    ARENA_EXISTS,
    ARENA_NOT_FOUND,
    ARENA_CREATED,
    ARENA_REMOVED,
    ARENA_SPAWN_SET,
    ARENA_ENABLED,
    ARENA_ALREADY_ENABLED,
    ARENA_DISABLED,
    ARENA_ALREADY_DISABLED,
    ARENA_INVENTORY_SET,
    ARENA_NOT_ENABLED,

    ARENA_INFO_HEADER,
    ARENA_INFO_STATE,
    ARENA_INFO_VERSUS,
    ARENA_INFO_WIN_COUNT,

    MATCH_JOINED,
    MATCH_WAIT_ONE_MORE,
    MATCH_ALREADY_JOINED,
    MATCH_IN_GAME,
    MATCH_LEFT,
    MATCH_CANNOT_LEAVE,
    MATCH_NOT_JOINED,
    MATCH_TELEPORT_IN,
    MATCH_GAME_START,
    MATCH_START_IN,
    MATCH_ROUND_START,
    MATCH_ROUND_WINNER,
    MATCH_CHAMPION,

    STATS_WIN,
    STATS_LOSE,
    STATS_RATIO,
    STATS_NONE,
    STATS_COOLDOWN,

    SIGN_LOOK_AT,
    SIGN_TAKEN,
    SIGN_REMOVED,
    SIGN_NOT_REGISTERED,
    SIGN_TITLE,
    SIGN_ARENA,
    SIGN_JOIN,
    SIGN_CANNOT_JOIN,

    STATE_WAITING,
    STATE_ONEMORE,
    STATE_COUNTDOWN,
    STATE_INGAME,

    SCOREBOARD_TITLE,
    SCOREBOARD_ENTRY,
}
