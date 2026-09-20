package net.ninebolt.onevsone.domain

/**
 * アリーナ。静的設定(有効化・スポーン)を担う永続エンティティ。
 * 進行中の試合状態は別集約の ArenaMatch が持ち、両者の 1:1 ペアリングは
 * ArenaRegistry が構造で保証する。装備中身は infrastructure が保持する。
 * immutable: 変更は copy() で新インスタンスを作り、レジストリと永続化へ置き換える。
 */
data class Arena(
    val id: Id,
    val enabled: Boolean = false,
    val spawn1: WorldPosition? = null,
    val spawn2: WorldPosition? = null
) {
    /** アリーナ識別子。永続化・看板・ファイル名と一致する名前を包む。 */
    @JvmInline
    value class Id(val name: String) {
        override fun toString(): String = name
    }

    val name: String get() = id.name

    fun spawn(slot: Int): WorldPosition? = when (slot) {
        0 -> spawn1
        1 -> spawn2
        else -> null
    }
}
