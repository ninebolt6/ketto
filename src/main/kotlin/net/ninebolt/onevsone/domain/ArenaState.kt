package net.ninebolt.onevsone.domain

/**
 * 1 アリーナの試合進行状態。表示文言・色は infrastructure 側の Messages が担う。
 */
enum class ArenaState {
    WAITING,
    ONEMORE,
    COUNTDOWN,
    ROUNDCOUNTDOWN,
    INGAME;

    fun isJoinable(): Boolean = this == WAITING || this == ONEMORE

    /**
     * この状態で敗北通知を受理するか。
     * 死亡は INGAME のみ、落下(非死亡)は INGAME/ROUNDCOUNTDOWN で受理する。
     * recordDefeat と resolvesVoidFall の双方がこの規則を共有する。
     */
    fun acceptsDefeat(cause: DefeatCause): Boolean = when (this) {
        INGAME -> true
        ROUNDCOUNTDOWN -> cause == DefeatCause.FALL
        else -> false
    }
}
