package net.ninebolt.onevsone.infrastructure.persistence

import net.ninebolt.onevsone.application.port.BackupRef
import net.ninebolt.onevsone.domain.MatchId
import net.ninebolt.onevsone.infrastructure.paper.PaperInventorySnapshot
import kotlin.uuid.Uuid

// match_id is forensic metadata only; the match aggregate has no durable identity to reference
class SqliteBackupStore(private val store: SqliteStore) {

    fun saveBackups(backups: List<PersistedBackup>) {
        store.atomic {
            backups.forEach { backup ->
                store.exec(
                    "INSERT INTO backups(backup_id, match_id, player_uuid, player_name, payload) VALUES (?, ?, ?, ?, ?)",
                    backup.ref.backupId.toString(),
                    backup.ref.matchId.value.toString(),
                    backup.ref.playerId?.toString(),
                    backup.ref.playerName,
                    InventoryPayloadCodec.encode(backup.snapshot)
                )
            }
        }
    }

    fun persistedBackups(): List<PersistedBackup> =
        store.query("SELECT backup_id, match_id, player_uuid, player_name, payload FROM backups ORDER BY rowid") { row ->
            PersistedBackup(
                BackupRef.restored(
                    backupId = Uuid.parse(row.getString("backup_id")),
                    matchId = MatchId.new(Uuid.parse(row.getString("match_id"))),
                    playerId = row.getString("player_uuid")?.let(Uuid::parseOrNull),
                    playerName = row.getString("player_name")
                ),
                InventoryPayloadCodec.decode(row.getString("payload"))
            )
        }

    fun deleteBackup(ref: BackupRef) {
        store.exec("DELETE FROM backups WHERE backup_id = ?", ref.backupId.toString())
    }

    fun backupFor(ref: BackupRef): PersistedBackup? =
        store.queryOne("SELECT payload FROM backups WHERE backup_id = ?", ref.backupId.toString()) { row ->
            PersistedBackup(ref, InventoryPayloadCodec.decode(row.getString("payload")))
        }
}

data class PersistedBackup(val ref: BackupRef, val snapshot: PaperInventorySnapshot)
