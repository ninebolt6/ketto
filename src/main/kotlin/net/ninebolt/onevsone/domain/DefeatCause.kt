package net.ninebolt.onevsone.domain

/** 敗北の通知経路。落下(非死亡)は ROUNDCOUNTDOWN 中も受理される。 */
enum class DefeatCause { DEATH, FALL }
