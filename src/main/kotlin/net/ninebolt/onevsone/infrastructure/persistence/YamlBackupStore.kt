package net.ninebolt.onevsone.infrastructure.persistence

import net.ninebolt.onevsone.application.port.BackupRef
import net.ninebolt.onevsone.domain.MatchId
import net.ninebolt.onevsone.infrastructure.paper.PaperInventorySnapshot
import org.bukkit.configuration.file.YamlConfiguration
import kotlin.uuid.Uuid

/**
 * Persistence for the inv.<name> section of players.yml (unrestored inventory
 * backups). The players/arena sections (participation registration) in the
 * same file are handled by YamlMatchStateRepository.
 * Record identity is decided by id (uuid when absent), so a reused name never
 * overwrites or deletes someone else's data. Conflicting old records are kept
 * by evacuating them to inv.<name>__<id>.
 */
class YamlBackupStore(private val store: YamlStore) {

    /** Bulk save at match start. On failure, nobody's record is changed. */
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
     * If a record under the same name key belongs to someone else, move it to an
     * evacuation key before writing. Records whose owner cannot be confirmed
     * (no uuid recorded) are also evacuated so they are not lost.
     */
    private fun evacuateForeignOwner(yaml: YamlConfiguration, path: String, ref: BackupRef) {
        if (!yaml.isConfigurationSection(path)) return
        val storedUuid = yaml.getString("$path.uuid")
        if (storedUuid != null && storedUuid == ref.playerId?.toString()) return
        val moved = "${path}__${yaml.getString("$path.id") ?: storedUuid ?: Uuid.random()}"
        RECORD_FIELDS.forEach { field -> yaml.set("$moved.$field", yaml.get("$path.$field")) }
        // The name cannot be recovered from the evacuation key, so the record carries it itself
        yaml.set("$moved.name", ref.playerName)
        yaml.set(path, null)
    }

    /**
     * List of unrestored backups. Old records without an identifier are
     * assigned one and written back, unifying future identity checks on id.
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
     * Deletion after a successful restore. Only records with a matching id
     * (uuid when unset) are deleted, so a reused name never deletes someone
     * else's data. Evacuated records are found by the same rule.
     */
    fun deleteBackup(ref: BackupRef) {
        val yaml = store.load(store.playersFile)
        val path = findRecordPath(yaml, ref) ?: return
        yaml.set(path, null)
        store.save(yaml, store.playersFile)
    }

    /** Fallback read for restore (when no in-memory snapshot exists). */
    fun backupFor(ref: BackupRef): PersistedBackup? {
        val yaml = store.load(store.playersFile)
        val path = findRecordPath(yaml, ref) ?: return null
        return PersistedBackup(ref, store.readSnapshot(yaml, path))
    }

    /** Path of the record ref points to. Searches all of inv for an id/uuid match, not just the name key. */
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
        /** Keys making up an inv record. Copied during evacuation. */
        val RECORD_FIELDS = listOf("armor", "item", "uuid", "id", "match")
    }
}

/** A complete backup as returned by the persistence layer. The payload is never exposed to application. */
data class PersistedBackup(val ref: BackupRef, val snapshot: PaperInventorySnapshot)
