package net.ninebolt.onevsone.infrastructure.persistence

import net.ninebolt.onevsone.application.port.BackupRef
import net.ninebolt.onevsone.domain.MatchId
import net.ninebolt.onevsone.infrastructure.paper.PaperInventorySnapshot
import org.bukkit.configuration.file.YamlConfiguration
import kotlin.uuid.Uuid

/**
 * players.yml の inv.<name> セクション(未復元インベントリバックアップ)の永続化。
 * 同一ファイルの players/arena セクション(参加登録)は YamlMatchStateRepository が担う。
 * レコードの同一性は id(無い場合は uuid)で判定し、名前の再利用で別人のデータを
 * 上書き・削除しない。衝突した旧レコードは inv.<name>__<id> へ退避して保持する。
 */
class YamlBackupStore(private val store: YamlStore) {

    /** 試合開始時の一括保存。失敗時は誰のレコードも変更しない。 */
    fun saveBackups(backups: List<PersistedBackup>) {
        val yaml = store.load(store.playersFile)
        backups.forEach { backup ->
            val path = "inv.${backup.ref.playerName}"
            evacuateForeignOwner(yaml, path, backup.ref)
            store.writeSnapshot(yaml, path, backup.snapshot)
            backup.ref.playerId?.let { yaml.set("$path.uuid", it.toString()) }
            yaml.set("$path.id", backup.ref.backupId.toString())
            yaml.set("$path.match", backup.ref.matchId.value.toString())
        }
        store.save(yaml, store.playersFile)
    }

    /**
     * 同じ名前キーに別人のレコードがあれば退避キーへ移してから書き込む。
     * 所有者を確認できない(uuid 未記録の)レコードも失わないよう退避対象にする。
     */
    private fun evacuateForeignOwner(yaml: YamlConfiguration, path: String, ref: BackupRef) {
        if (!yaml.isConfigurationSection(path)) return
        val storedUuid = yaml.getString("$path.uuid")
        if (storedUuid != null && storedUuid == ref.playerId?.toString()) return
        val moved = "${path}__${yaml.getString("$path.id") ?: storedUuid ?: Uuid.random()}"
        RECORD_FIELDS.forEach { field -> yaml.set("$moved.$field", yaml.get("$path.$field")) }
        // 退避キーからは名前を復元できないため、レコード自身に名前を持たせる
        yaml.set("$moved.name", ref.playerName)
        yaml.set(path, null)
    }

    /**
     * 未復元バックアップ一覧。識別子の無い旧レコードは採番して書き戻し、
     * 以後の同一性判定を id に一本化する。
     */
    fun persistedBackups(): List<PersistedBackup> {
        val yaml = store.load(store.playersFile)
        val inv = yaml.getConfigurationSection("inv") ?: return emptyList()
        var stamped = false
        val records = inv.getKeys(false).map { key ->
            val path = "inv.$key"
            val name = yaml.getString("$path.name") ?: key
            val id = yaml.getString("$path.id")?.let(::parseUuid) ?: Uuid.random().also {
                yaml.set("$path.id", it.toString())
                stamped = true
            }
            val uuid = yaml.getString("$path.uuid")?.let(::parseUuid)
            val matchId = yaml.getString("$path.match")?.let(::parseUuid)?.let { MatchId.new(it) }
                ?: MatchId.new()
            PersistedBackup(BackupRef.restored(id, matchId, uuid, name), store.readSnapshot(yaml, path))
        }
        if (stamped) store.save(yaml, store.playersFile)
        return records
    }

    /**
     * 復元完了後の削除。id(未設定時は uuid)が一致する記録だけを消し、
     * 名前の再利用で別人のデータを消さない。退避レコードも同じ規則で探す。
     */
    fun deleteBackup(ref: BackupRef) {
        val yaml = store.load(store.playersFile)
        val path = findRecordPath(yaml, ref) ?: return
        yaml.set(path, null)
        store.save(yaml, store.playersFile)
    }

    /** restore のフォールバック読み出し(メモリ上のスナップショットが無い場合)。 */
    fun backupFor(ref: BackupRef): PersistedBackup? {
        val yaml = store.load(store.playersFile)
        val path = findRecordPath(yaml, ref) ?: return null
        return PersistedBackup(ref, store.readSnapshot(yaml, path))
    }

    /** ref が指すレコードのパス。名前キーに限らず inv 配下を id/uuid 一致で探す。 */
    private fun findRecordPath(yaml: YamlConfiguration, ref: BackupRef): String? =
        yaml.getConfigurationSection("inv")?.getKeys(false)
            ?.map { "inv.$it" }
            ?.firstOrNull { backupMatches(yaml, it, ref) }

    private fun backupMatches(yaml: YamlConfiguration, path: String, ref: BackupRef): Boolean {
        val storedId = yaml.getString("$path.id")
        return if (storedId != null) {
            storedId == ref.backupId.toString()
        } else {
            yaml.getString("$path.uuid") == ref.playerId?.toString()
        }
    }

    private fun parseUuid(raw: String): Uuid? = Uuid.parseOrNull(raw)

    private companion object {
        /** inv レコードの構成キー。退避時のコピー対象。 */
        val RECORD_FIELDS = listOf("armor", "item", "uuid", "id", "match")
    }
}

/** 永続化層が返すバックアップ一式。実データはアプリケーションへ出さない。 */
data class PersistedBackup(val ref: BackupRef, val snapshot: PaperInventorySnapshot)
