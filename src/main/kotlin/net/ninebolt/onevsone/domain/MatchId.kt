package net.ninebolt.onevsone.domain

import kotlin.uuid.Uuid

/** 1 回の試合(バックアップ〜終了復元まで)を識別するトークン的 ID。 */
@JvmInline
value class MatchId private constructor(val value: Uuid) {
    companion object {
        fun new(): MatchId = MatchId(Uuid.random())
        fun new(value: Uuid): MatchId = MatchId(value)
    }
    override fun toString(): String = value.toString()
}
