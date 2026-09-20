package net.ninebolt.onevsone.application.port

import net.ninebolt.onevsone.domain.Arena

/**
 * アリーナ定義の永続化。
 * 装備(キット)の中身は扱わず、アリーナ ID 経由で KitPort が触れる。
 * ロビーは LobbyRepository、看板座標は ArenaSignRepository が担う。
 * 永続化形式(index ファイルや個別ファイルの配置)は実装の内部事情とする。
 */
interface ArenaRepository {
    /** 登録順の全アリーナ。無効名・重複・読み取り不能な項目はスキップされる。 */
    fun loadAll(): List<Arena>

    /** 無効名は null。ファイル欠損は PersistenceFailure。未作成ファイルは既定値のアリーナを返す。 */
    fun find(name: String): Arena?

    /** アリーナを保存し、未登録なら登録順の末尾に追加する。 */
    fun save(arena: Arena)

    /** 定義と関連する永続データを削除し、登録一覧から除外する。 */
    fun delete(name: String)
}
