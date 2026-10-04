package net.ninebolt.ketto.infrastructure.persistence

import net.ninebolt.ketto.application.port.BackupRef
import net.ninebolt.ketto.domain.MatchId
import net.ninebolt.ketto.infrastructure.paper.PaperInventorySnapshot
import java.sql.ResultSet
import java.util.logging.Logger
import kotlin.uuid.Uuid

// match_id is forensic metadata only; the match aggregate has no durable identity to reference
class SqliteBackupStore(
    private val store: SqliteStore,
    private val logger: Logger = Logger.getLogger(SqliteBackupStore::class.java.name),
) {

    fun saveBackups(backups: List<PersistedBackup>) {
        store.atomic {
            backups.forEach { backup ->
                store.exec(
                    "INSERT OR REPLACE INTO backups(backup_id, match_id, player_uuid, player_name, payload) VALUES (?, ?, ?, ?, ?)",
                    backup.ref.backupId.toString(),
                    backup.ref.matchId.value.toString(),
                    backup.ref.playerId.toString(),
                    backup.ref.playerName,
                    InventoryPayloadCodec.encode(backup.snapshot),
                )
            }
        }
    }

    // strict: returning null would let the next match's INSERT OR REPLACE silently overwrite the row and its payload
    fun findPending(playerId: Uuid): BackupRef? = store.queryOne(
        "SELECT backup_id, match_id, player_uuid, player_name FROM backups WHERE player_uuid = ?",
        playerId.toString(),
    ) { toRef(it) }

    // shutdown restore must not be aborted by a single corrupt row
    fun pendingRefs(): List<BackupRef> = store.query("SELECT backup_id, match_id, player_uuid, player_name FROM backups ORDER BY rowid") { row ->
        try {
            toRef(row)
        } catch (e: IllegalArgumentException) {
            logger.warning("Ignoring unreadable backup row ${row.getString("backup_id")} for ${row.getString("player_name")}")
            null
        }
    }.filterNotNull()

    private fun toRef(row: ResultSet): BackupRef = BackupRef.restored(
        backupId = Uuid.parse(row.getString("backup_id")),
        matchId = MatchId.new(Uuid.parse(row.getString("match_id"))),
        playerId = Uuid.parse(row.getString("player_uuid")),
        playerName = row.getString("player_name"),
    )

    fun deleteBackup(ref: BackupRef) {
        store.exec("DELETE FROM backups WHERE backup_id = ?", ref.backupId.toString())
    }

    fun backupFor(ref: BackupRef): PersistedBackup? = store.queryOne("SELECT payload FROM backups WHERE backup_id = ?", ref.backupId.toString()) { row ->
        PersistedBackup(ref, InventoryPayloadCodec.decode(row.getString("payload")))
    }
}

data class PersistedBackup(val ref: BackupRef, val snapshot: PaperInventorySnapshot)
