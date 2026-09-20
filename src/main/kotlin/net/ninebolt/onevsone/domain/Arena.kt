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
    /**
     * アリーナ識別子。永続化・看板・ファイル名と一致する名前を包む。
     * 生成は companion のファクトリ経由のみで、受理規則を満たさない
     * インスタンスは作れない。
     */
    @JvmInline
    value class Id private constructor(val name: String) {
        override fun toString(): String = name

        companion object {
            /**
             * アリーナ名の受理規則。永続化ファイル名に直結するため、パスに使えない
             * 文字や予約名(players)を拒否する。
             */
            private fun isValidName(name: String): Boolean =
                name.isNotBlank() &&
                    name.length <= 64 &&
                    name.none { it == '/' || it == '\\' || it == '.' || it.isISOControl() } &&
                    !name.equals("players", ignoreCase = true)

            /** コマンド引数・永続化データなどの外部入力からの変換。不正名は null。 */
            fun of(name: String): Id? = if (isValidName(name)) Id(name) else null

            /** 妥当性が分かっている名前向け。不正名は IllegalArgumentException。 */
            operator fun invoke(name: String): Id =
                of(name) ?: throw IllegalArgumentException("invalid arena name: '$name'")
        }
    }

    val name: String get() = id.name

    fun spawn(slot: Int): WorldPosition? = when (slot) {
        0 -> spawn1
        1 -> spawn2
        else -> null
    }
}
