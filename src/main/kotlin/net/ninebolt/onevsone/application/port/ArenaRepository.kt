package net.ninebolt.onevsone.application.port

import net.ninebolt.onevsone.domain.ArenaDefinition

/**
 * アリーナ定義の永続化。
 * 装備(キット)の中身は扱わず、アリーナ ID 経由で KitPort が触れる。
 * ロビーは LobbyRepository、看板座標は ArenaSignRepository が担う。
 */
interface ArenaRepository {
    /** arenalist 上の名前一覧(無効名を含み得る。呼び出し側が検証する)。 */
    fun arenaNames(): List<String>
    fun saveArenaNames(names: List<String>)
    /** 無効名は null。ファイル欠損は PersistenceFailure。未作成ファイルは既定値の定義を返す。 */
    fun find(name: String): ArenaDefinition?
    fun save(arena: ArenaDefinition)
    fun delete(name: String)
}
