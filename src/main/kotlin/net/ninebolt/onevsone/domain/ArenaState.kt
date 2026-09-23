package net.ninebolt.onevsone.domain

/**
 * Match progression state of one arena. Display text and colors are handled by
 * Messenger on the infrastructure side.
 */
enum class ArenaState {
    WAITING,
    ONEMORE,
    COUNTDOWN,
    ROUNDCOUNTDOWN,
    INGAME;

    fun isJoinable(): Boolean = this == WAITING || this == ONEMORE

    /**
     * Whether this state accepts a defeat notification.
     * Death is accepted only in INGAME; falls (non-death) are accepted in
     * INGAME/ROUNDCOUNTDOWN. Both recordDefeat and resolvesVoidFall share
     * this rule.
     */
    fun acceptsDefeat(cause: DefeatCause): Boolean = when (this) {
        INGAME -> true
        ROUNDCOUNTDOWN -> cause == DefeatCause.FALL
        else -> false
    }
}
