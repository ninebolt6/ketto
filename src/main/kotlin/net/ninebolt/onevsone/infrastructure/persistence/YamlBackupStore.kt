package net.ninebolt.onevsone.infrastructure.persistence

import net.ninebolt.onevsone.application.port.BackupRef
import net.ninebolt.onevsone.domain.MatchId
import net.ninebolt.onevsone.infrastructure.paper.PaperInventorySnapshot
import org.bukkit.configuration.file.YamlConfiguration
import kotlin.uuid.Uuid

/**
 * players.yml の inv.<name> セクション(未復元インベントリバックアップ)の永続化。
 * 同一ファイルの players/arena セクション(参加登録)は YamlMatchStateRepository が担う。
 */
class YamlBackupStore(private val store: YamlStore) {

    /** 試合開始時の一括保存。失敗時は誰のレコードも変更しない。 */
    fun saveBackups(backups: List<PersistedBackup>) {
        val yaml = store.load(store.playersFile)
        backups.forEach { backup ->
            val path = "inv.${backup.ref.playerName}"
            store.writeSnapshot(yaml, path, backup.snapshot)
            backup.ref.playerId?.let { yaml.set("$path.uuid", it.toString()) }
            yaml.set("$path.id", backup.ref.backupId.toString())
            yaml.set("$path.match", backup.ref.matchId.value.toString())
        }
        store.save(yaml, store.playersFile)
    }

    fun persistedBackups(): List<PersistedBackup> {
        val yaml = store.load(store.playersFile)
        val inv = yaml.getConfigurationSection("inv") ?: return emptyList()
        return inv.getKeys(false).map { name ->
            val snapshot = store.readSnapshot(yaml, "inv.$name")
            val uuid = yaml.getString("inv.$name.uuid")?.let(::parseUuid)
            val backupId = yaml.getString("inv.$name.id")?.let(::parseUuid) ?: Uuid.random()
            val matchId = yaml.getString("inv.$name.match")?.let(::parseUuid)?.let { MatchId.new(it) }
                ?: MatchId.newId()
            PersistedBackup(BackupRef.restored(backupId, matchId, uuid, name), snapshot)
        }
    }

    /**
     * 復元完了後の削除。backupId(未設定時は uuid)が一致する記録だけを消し、
     * 名前の再利用で別人のデータを消さない。
     */
    fun deleteBackup(ref: BackupRef) {
        val yaml = store.load(store.playersFile)
        val path = "inv.${ref.playerName}"
        if (!yaml.isConfigurationSection(path)) return
        if (!backupMatches(yaml, path, ref)) return
        yaml.set(path, null)
        store.save(yaml, store.playersFile)
    }

    /** restore のフォールバック読み出し(メモリ上のスナップショットが無い場合)。 */
    fun backupFor(ref: BackupRef): PersistedBackup? {
        val yaml = store.load(store.playersFile)
        val path = "inv.${ref.playerName}"
        if (!yaml.isConfigurationSection(path)) return null
        if (!backupMatches(yaml, path, ref)) return null
        return PersistedBackup(ref, store.readSnapshot(yaml, path))
    }

    private fun backupMatches(yaml: YamlConfiguration, path: String, ref: BackupRef): Boolean {
        val storedId = yaml.getString("$path.id")
        return if (storedId != null) {
            storedId == ref.backupId.toString()
        } else {
            yaml.getString("$path.uuid") == ref.playerId?.toString()
        }
    }

    private fun parseUuid(raw: String): Uuid? = Uuid.parseOrNull(raw)
}

/** 永続化層が返すバックアップ一式。実データはアプリケーションへ出さない。 */
data class PersistedBackup(val ref: BackupRef, val snapshot: PaperInventorySnapshot)
