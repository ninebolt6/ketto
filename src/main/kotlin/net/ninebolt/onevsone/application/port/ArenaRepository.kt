package net.ninebolt.onevsone.application.port

import net.ninebolt.onevsone.domain.ArenaDefinition
import net.ninebolt.onevsone.domain.WorldPosition

/**
 * アリーナ定義とロビー/看板座標の永続化。
 * 装備(キット)の中身は扱わず、アリーナ ID 経由で PlayerEquipmentPort が触れる。
 */
interface ArenaRepository {
    /** arenalist 上の名前一覧(無効名を含み得る。呼び出し側が検証する)。 */
    fun arenaNames(): List<String>
    fun saveArenaNames(names: List<String>)
    /** 無効名は null。ファイル欠損は PersistenceFailure。未作成ファイルは既定値の定義を返す。 */
    fun find(name: String): ArenaDefinition?
    fun save(arena: ArenaDefinition)
    fun delete(name: String)

    fun lobby(): WorldPosition?
    fun setLobby(position: WorldPosition)

    fun signLocation(arenaName: String): WorldPosition?
    fun setSign(arenaName: String, position: WorldPosition)
    fun clearSign(arenaName: String)
    fun signOwner(world: String, x: Double, y: Double, z: Double): String?
}
