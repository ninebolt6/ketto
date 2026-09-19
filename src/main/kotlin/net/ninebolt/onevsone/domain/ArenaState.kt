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

    /** Join 看板で参加を受け付けられる状態か。 */
    fun isJoinable(): Boolean = this == WAITING || this == ONEMORE
}
