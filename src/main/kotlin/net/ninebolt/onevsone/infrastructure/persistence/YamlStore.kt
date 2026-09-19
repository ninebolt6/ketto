package net.ninebolt.onevsone.infrastructure.persistence

import net.ninebolt.onevsone.application.port.BackupRef
import net.ninebolt.onevsone.application.port.PersistenceFailure
import net.ninebolt.onevsone.domain.MatchId
import net.ninebolt.onevsone.domain.WorldPosition
import net.ninebolt.onevsone.infrastructure.paper.PaperInventorySnapshot
import org.bukkit.configuration.InvalidConfigurationException
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ItemStack
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.logging.Logger

/**
 * 共通 YAML I/O とファイル配置。temp+replace の原子的保存を維持する。
 * 破損・I/O 失敗は PersistenceFailure に変換する。
 */
class YamlStore(folder: File, private val logger: Logger) {

    internal val arenaDir = File(folder, "arena")
    internal val statusDir = File(folder, "status")
    internal val statsDir = File(folder, "stats")
    internal val arenaListFile = File(folder, "arenalist.yml")
    internal val playersFile = File(statusDir, "players.yml")
    internal val configFile = File(folder, "config.yml")

    private var config: YamlConfiguration? = null

    init {
        folder.mkdirs()
        arenaDir.mkdirs()
        statusDir.mkdirs()
        statsDir.mkdirs()
    }

    internal fun arenaFile(name: String) = File(arenaDir, "$name.yml")
    internal fun statusFile(name: String) = File(statusDir, "$name.yml")
    internal fun statsFile(uuid: UUID) = File(statsDir, "$uuid.yml")

    internal fun load(file: File): YamlConfiguration {
        if (!file.exists()) return YamlConfiguration()
        val yaml = YamlConfiguration()
        try {
            yaml.load(file)
        } catch (e: InvalidConfigurationException) {
            logger.warning("Failed to parse ${file.path}: ${e.message}")
            throw PersistenceFailure("Unreadable YAML file: ${file.path}", e)
        } catch (e: IOException) {
            logger.warning("Failed to read ${file.path}: ${e.message}")
            throw PersistenceFailure("Unreadable YAML file: ${file.path}", e)
        }
        return yaml
    }

    internal fun warn(message: String) = logger.warning(message)

    internal fun save(yaml: YamlConfiguration, file: File) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            yaml.save(tmp)
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            logger.warning("Failed to save ${file.path}: ${e.message}")
            throw PersistenceFailure("Could not save YAML file: ${file.path}", e)
        }
    }

    internal fun config(): YamlConfiguration {
        config?.let { return it }
        val loaded = load(configFile)
        config = loaded
        return loaded
    }

    internal fun saveConfig() = save(config(), configFile)

    // ---- コーデック --------------------------------------------------------

    internal fun readLocation(yaml: YamlConfiguration, path: String): WorldPosition? {
        val world = yaml.getString("$path.world") ?: return null
        return WorldPosition(
            world = world,
            x = yaml.getDouble("$path.x"),
            y = yaml.getDouble("$path.y"),
            z = yaml.getDouble("$path.z"),
            yaw = yaml.getDouble("$path.yaw").toFloat(),
            pitch = yaml.getDouble("$path.pitch").toFloat()
        )
    }

    internal fun writeLocation(yaml: YamlConfiguration, path: String, loc: WorldPosition) {
        yaml.set("$path.world", loc.world)
        yaml.set("$path.x", loc.x)
        yaml.set("$path.y", loc.y)
        yaml.set("$path.z", loc.z)
        yaml.set("$path.yaw", loc.yaw.toDouble())
        yaml.set("$path.pitch", loc.pitch.toDouble())
    }

    internal fun readSnapshot(yaml: YamlConfiguration, path: String): PaperInventorySnapshot {
        val armor = (yaml.getList("$path.armor") ?: emptyList()).map { it as? ItemStack }
        val items = (yaml.getList("$path.item") ?: emptyList()).map { it as? ItemStack }
        return PaperInventorySnapshot(armor, items)
    }

    internal fun writeSnapshot(yaml: YamlConfiguration, path: String, snapshot: PaperInventorySnapshot) {
        yaml.set("$path.armor", snapshot.armor)
        yaml.set("$path.item", snapshot.items)
    }

    // ---- アリーナ装備(arena/<name>.yml の inventory セクション) -------------

    fun loadArenaKit(arenaName: String): PaperInventorySnapshot =
        readSnapshot(load(arenaFile(arenaName)), "inventory")

    fun saveArenaKit(arenaName: String, kit: PaperInventorySnapshot) {
        val file = arenaFile(arenaName)
        val yaml = load(file)
        writeSnapshot(yaml, "inventory", kit)
        save(yaml, file)
    }

    // ---- 未復元バックアップ(players.yml の inv.<name> セクション) -------------

    /** 試合開始時の一括保存。失敗時は誰のレコードも変更しない。 */
    fun saveBackups(backups: List<PersistedBackup>) {
        val yaml = load(playersFile)
        for (backup in backups) {
            val path = "inv.${backup.ref.playerName}"
            writeSnapshot(yaml, path, backup.snapshot)
            backup.ref.playerId?.let { yaml.set("$path.uuid", it.toString()) }
            yaml.set("$path.id", backup.ref.backupId.toString())
            yaml.set("$path.match", backup.ref.matchId.value.toString())
        }
        save(yaml, playersFile)
    }

    fun persistedBackups(): List<PersistedBackup> {
        val yaml = load(playersFile)
        val inv = yaml.getConfigurationSection("inv") ?: return emptyList()
        val result = mutableListOf<PersistedBackup>()
        for (name in inv.getKeys(false)) {
            val snapshot = readSnapshot(yaml, "inv.$name")
            val uuid = yaml.getString("inv.$name.uuid")?.let { parseUuid(it) }
            val backupId = yaml.getString("inv.$name.id")?.let { parseUuid(it) } ?: UUID.randomUUID()
            val matchId = yaml.getString("inv.$name.match")?.let { parseUuid(it) }?.let { MatchId(it) }
                ?: MatchId.newId()
            result.add(PersistedBackup(BackupRef(backupId, matchId, uuid, name), snapshot))
        }
        return result
    }

    /**
     * 復元完了後の削除。backupId(未設定時は uuid)が一致する記録だけを消し、
     * 名前の再利用で別人のデータを消さない。
     */
    fun deleteBackup(ref: BackupRef) {
        val yaml = load(playersFile)
        val path = "inv.${ref.playerName}"
        if (!yaml.isConfigurationSection(path)) return
        if (!backupMatches(yaml, path, ref)) return
        yaml.set(path, null)
        save(yaml, playersFile)
    }

    /** restore のフォールバック読み出し(メモリ上のスナップショットが無い場合)。 */
    fun backupFor(ref: BackupRef): PersistedBackup? {
        val yaml = load(playersFile)
        val path = "inv.${ref.playerName}"
        if (!yaml.isConfigurationSection(path)) return null
        if (!backupMatches(yaml, path, ref)) return null
        return PersistedBackup(ref, readSnapshot(yaml, path))
    }

    private fun backupMatches(yaml: YamlConfiguration, path: String, ref: BackupRef): Boolean {
        val storedId = yaml.getString("$path.id")
        return if (storedId != null) {
            storedId == ref.backupId.toString()
        } else {
            yaml.getString("$path.uuid") == ref.playerId?.toString()
        }
    }

    private fun parseUuid(raw: String): UUID? =
        try {
            UUID.fromString(raw)
        } catch (e: IllegalArgumentException) {
            null
        }
}

/** 永続化層が返すバックアップ一式。実データはアプリケーションへ出さない。 */
data class PersistedBackup(val ref: BackupRef, val snapshot: PaperInventorySnapshot)
