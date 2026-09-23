package net.ninebolt.onevsone.infrastructure.persistence

import net.ninebolt.onevsone.application.port.BackupRef
import net.ninebolt.onevsone.domain.MatchId
import net.ninebolt.onevsone.infrastructure.paper.PaperInventorySnapshot
import kotlin.uuid.Uuid

/**
 * Persistence for unrestored inventory backups. Rows are identified by
 * backup_id, so identity never collides on a reused player name.
 * match_id is forensic metadata only: the match aggregate has no durable
 * identity to reference.
 */
class SqliteBackupStore(private val store: SqliteStore) {

    /** Bulk save at match start; one transaction so nobody's record is half-written. */
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

    /** Unrestored backups in insertion order. */
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

    /** Deletion after a successful restore; only the matching backup_id is removed. */
    fun deleteBackup(ref: BackupRef) {
        store.exec("DELETE FROM backups WHERE backup_id = ?", ref.backupId.toString())
    }

    /** Fallback read for restore (when no in-memory snapshot exists). */
    fun backupFor(ref: BackupRef): PersistedBackup? =
        store.queryOne("SELECT payload FROM backups WHERE backup_id = ?", ref.backupId.toString()) { row ->
            PersistedBackup(ref, InventoryPayloadCodec.decode(row.getString("payload")))
        }
}

/** A complete backup as returned by the persistence layer. The payload is never exposed to application. */
data class PersistedBackup(val ref: BackupRef, val snapshot: PaperInventorySnapshot)
