package net.ninebolt.onevsone.infrastructure.persistence

import net.ninebolt.onevsone.application.port.BackupRef
import net.ninebolt.onevsone.domain.MatchId
import net.ninebolt.onevsone.infrastructure.paper.PaperInventorySnapshot
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

    fun pendingFor(playerId: Uuid): BackupRef? = store.queryOne(
        "SELECT backup_id, match_id, player_uuid, player_name FROM backups WHERE player_uuid = ?",
        playerId.toString(),
    ) { toRef(it) }

    // pendingRefs feeds startup recovery; a single corrupt row must not abort the whole scan
    fun pendingRefs(): List<BackupRef> = store.query("SELECT backup_id, match_id, player_uuid, player_name FROM backups ORDER BY rowid") { row ->
        toRef(row)
    }.filterNotNull()

    private fun toRef(row: ResultSet): BackupRef? {
        val playerId = Uuid.parseOrNull(row.getString("player_uuid"))
        if (playerId == null) {
            logger.warning("Ignoring backup row ${row.getString("backup_id")} for ${row.getString("player_name")}: no owner uuid")
            return null
        }
        return BackupRef.restored(
            backupId = Uuid.parse(row.getString("backup_id")),
            matchId = MatchId.new(Uuid.parse(row.getString("match_id"))),
            playerId = playerId,
            playerName = row.getString("player_name"),
        )
    }

    fun deleteBackup(ref: BackupRef) {
        store.exec("DELETE FROM backups WHERE backup_id = ?", ref.backupId.toString())
    }

    fun backupFor(ref: BackupRef): PersistedBackup? = store.queryOne("SELECT payload FROM backups WHERE backup_id = ?", ref.backupId.toString()) { row ->
        PersistedBackup(ref, InventoryPayloadCodec.decode(row.getString("payload")))
    }
}

data class PersistedBackup(val ref: BackupRef, val snapshot: PaperInventorySnapshot)
