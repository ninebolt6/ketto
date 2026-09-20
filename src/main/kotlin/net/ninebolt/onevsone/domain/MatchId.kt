package net.ninebolt.onevsone.domain

import kotlin.uuid.Uuid

/** 1 回の試合(バックアップ〜終了復元まで)を識別するトークン的 ID。 */
@JvmInline
value class MatchId(val value: Uuid) {
    companion object {
        fun newId(): MatchId = MatchId(Uuid.random())
    }
    override fun toString(): String = value.toString()
}
