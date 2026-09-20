package net.ninebolt.onevsone.domain

/**
 * ArenaMatch の操作結果。match は遷移後の新しい状態(拒否時は変化なしの同一インスタンス)。
 * 呼び出し側は outcome を見てから match をレジストリへ書き戻す。
 */
data class Transition<out O>(val match: ArenaMatch, val outcome: O)
