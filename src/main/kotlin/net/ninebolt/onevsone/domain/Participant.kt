package net.ninebolt.onevsone.domain

import kotlin.uuid.Uuid

/** Bukkit の Player もインベントリも持たず、識別子と表示名だけを持つ。 */
data class Participant private constructor(val id: Uuid, val name: String) {
    companion object {
        /** 新規参加者。識別子は内部で発番する。 */
        fun new(name: String): Participant = new(Uuid.random(), name)

        /** 既存プレイヤーの識別子が分かっている場合向け。 */
        fun new(id: Uuid, name: String): Participant {
            require(name.isNotBlank()) { "participant name must not be blank" }
            return Participant(id, name)
        }
    }
}
