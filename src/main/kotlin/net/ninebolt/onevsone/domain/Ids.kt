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

/**
 * アリーナ名の受理規則。永続化ファイル名に直結するため、パスに使えない文字や
 * 予約名(players)を拒否する。arena ファイルの読み書きと管理コマンドの双方が使う。
 */
fun isValidArenaName(name: String): Boolean =
    name.isNotBlank() &&
        name.length <= 64 &&
        name.none { it == '/' || it == '\\' || it == '.' || it.isISOControl() } &&
        !name.equals("players", ignoreCase = true)
