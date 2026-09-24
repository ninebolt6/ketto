package net.ninebolt.onevsone.infrastructure.paper.message

import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.PlayerStats
import java.util.Locale

sealed class Message(val key: MessageKey, vararg val args: Arg) {

    sealed interface Arg {
        val name: String
    }

    data class Str(override val name: String, val value: String) : Arg

    data class Nested(override val name: String, val message: Message) : Arg

    data object Prefix : Message(MessageKey.PREFIX)

    data object UsageRoot : Message(MessageKey.USAGE_ROOT)
    data object UsageArena : Message(MessageKey.USAGE_ARENA)
    data object UsageArenaOps : Message(MessageKey.USAGE_ARENA_OPS)
    data object UsageLobby : Message(MessageKey.USAGE_LOBBY)
    data object UsageCreate : Message(MessageKey.USAGE_CREATE)
    data object UsageRemove : Message(MessageKey.USAGE_REMOVE)
    data object UsageSpawn : Message(MessageKey.USAGE_SPAWN)
    data object UsageEnable : Message(MessageKey.USAGE_ENABLE)
    data object UsageDisable : Message(MessageKey.USAGE_DISABLE)
    data object UsageKit : Message(MessageKey.USAGE_KIT)
    data object UsageSign : Message(MessageKey.USAGE_SIGN)
    data object UsageSignSet : Message(MessageKey.USAGE_SIGN_SET)
    data object UsageSignRemove : Message(MessageKey.USAGE_SIGN_REMOVE)

    data object CommandNoPermission : Message(MessageKey.COMMAND_NO_PERMISSION)
    data object CommandPlayerOnly : Message(MessageKey.COMMAND_PLAYER_ONLY)
    data object CommandBlocked : Message(MessageKey.COMMAND_BLOCKED)

    data object LobbySet : Message(MessageKey.LOBBY_SET)

    data object ArenaExists : Message(MessageKey.ARENA_EXISTS)
    data object ArenaNotFound : Message(MessageKey.ARENA_NOT_FOUND)
    class ArenaCreated(name: String) : Message(MessageKey.ARENA_CREATED, Str("name", name))
    class ArenaRemoved(name: String) : Message(MessageKey.ARENA_REMOVED, Str("name", name))
    class ArenaSpawnSet(name: String, slot: Int) :
        Message(MessageKey.ARENA_SPAWN_SET, Str("name", name), Str("slot", "$slot"))
    class ArenaEnabled(name: String) : Message(MessageKey.ARENA_ENABLED, Str("name", name))
    data object ArenaAlreadyEnabled : Message(MessageKey.ARENA_ALREADY_ENABLED)
    class ArenaDisabled(name: String) : Message(MessageKey.ARENA_DISABLED, Str("name", name))
    data object ArenaAlreadyDisabled : Message(MessageKey.ARENA_ALREADY_DISABLED)
    class ArenaInventorySet(name: String) : Message(MessageKey.ARENA_INVENTORY_SET, Str("name", name))
    data object ArenaNotEnabled : Message(MessageKey.ARENA_NOT_ENABLED)

    class ArenaInfoHeader(name: String) : Message(MessageKey.ARENA_INFO_HEADER, Str("name", name))
    class ArenaInfoState(state: ArenaState) :
        Message(MessageKey.ARENA_INFO_STATE, Nested("display", StateDisplay(state)))
    class ArenaInfoVersus(name1: String, name2: String) :
        Message(MessageKey.ARENA_INFO_VERSUS, Str("name1", name1), Str("name2", name2))
    class ArenaInfoWinCount(wins1: Int, wins2: Int) :
        Message(MessageKey.ARENA_INFO_WIN_COUNT, Str("wins1", "$wins1"), Str("wins2", "$wins2"))

    class MatchJoined(name: String) : Message(MessageKey.MATCH_JOINED, Str("name", name))
    data object MatchWaitOneMore : Message(MessageKey.MATCH_WAIT_ONE_MORE)
    data object MatchAlreadyJoined : Message(MessageKey.MATCH_ALREADY_JOINED)
    data object MatchInGame : Message(MessageKey.MATCH_IN_GAME)
    data object MatchLeft : Message(MessageKey.MATCH_LEFT)
    data object MatchCannotLeave : Message(MessageKey.MATCH_CANNOT_LEAVE)
    data object MatchNotJoined : Message(MessageKey.MATCH_NOT_JOINED)
    class MatchTeleportIn(n: Int) : Message(MessageKey.MATCH_TELEPORT_IN, Str("n", "$n"))
    data object MatchGameStart : Message(MessageKey.MATCH_GAME_START)
    class MatchStartIn(n: Int) : Message(MessageKey.MATCH_START_IN, Str("n", "$n"))
    data object MatchRoundStart : Message(MessageKey.MATCH_ROUND_START)
    class MatchRoundWinner(round: Int, name: String) :
        Message(MessageKey.MATCH_ROUND_WINNER, Str("round", "$round"), Str("name", name))
    class MatchChampion(arena: String, name: String) :
        Message(MessageKey.MATCH_CHAMPION, Str("arena", arena), Str("name", name))

    class StatsWin(wins: Int) : Message(MessageKey.STATS_WIN, Str("wins", "$wins"))
    class StatsLose(losses: Int) : Message(MessageKey.STATS_LOSE, Str("losses", "$losses"))
    class StatsRatio(stats: PlayerStats) :
        Message(MessageKey.STATS_RATIO, Str("ratio", "%.2f".format(Locale.ROOT, stats.ratio)))
    data object StatsNone : Message(MessageKey.STATS_NONE)
    data object StatsCooldown : Message(MessageKey.STATS_COOLDOWN)

    data object SignLookAt : Message(MessageKey.SIGN_LOOK_AT)
    data object SignTaken : Message(MessageKey.SIGN_TAKEN)
    class SignRemoved(name: String) : Message(MessageKey.SIGN_REMOVED, Str("name", name))
    data object SignNotRegistered : Message(MessageKey.SIGN_NOT_REGISTERED)
    data object SignTitle : Message(MessageKey.SIGN_TITLE)
    class SignArena(name: String) : Message(MessageKey.SIGN_ARENA, Str("name", name))
    data object SignJoin : Message(MessageKey.SIGN_JOIN)
    data object SignCannotJoin : Message(MessageKey.SIGN_CANNOT_JOIN)

    class StateDisplay(state: ArenaState) : Message(when (state) {
        ArenaState.WAITING -> MessageKey.STATE_WAITING
        ArenaState.ONEMORE -> MessageKey.STATE_ONEMORE
        ArenaState.COUNTDOWN -> MessageKey.STATE_COUNTDOWN
        ArenaState.ROUNDCOUNTDOWN, ArenaState.INGAME -> MessageKey.STATE_INGAME
    })

    class ScoreboardTitle(arena: String) : Message(MessageKey.SCOREBOARD_TITLE, Str("arena", arena))
    class ScoreboardEntry(name: String) : Message(MessageKey.SCOREBOARD_ENTRY, Str("name", name))
}

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
