package net.ninebolt.onevsone.domain

enum class ArenaState {
    WAITING,
    ONEMORE,
    COUNTDOWN,
    ROUNDCOUNTDOWN,
    INGAME;

    fun isJoinable(): Boolean = this == WAITING || this == ONEMORE

    fun acceptsDefeat(cause: DefeatCause): Boolean = when (this) {
        INGAME -> true
        ROUNDCOUNTDOWN -> cause == DefeatCause.FALL
        else -> false
    }
}
