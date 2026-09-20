package net.ninebolt.onevsone.infrastructure.persistence

import net.ninebolt.onevsone.application.port.PersistenceFailure
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
import java.util.logging.Logger
import kotlin.uuid.Uuid

/**
 * 共通 YAML I/O・ファイル配置・コーデック。temp+replace の原子的保存を維持する。
 * 破損・I/O 失敗は PersistenceFailure に変換する。
 * 各セクションの読み書きは Yaml*Repository / Yaml*Store が担う。
 */
class YamlStore(folder: File, private val logger: Logger) {

    internal val arenaDir = File(folder, "arena")
    internal val statusDir = File(folder, "status")
    internal val statsDir = File(folder, "stats")
    internal val arenaListFile = File(folder, "arenalist.yml")
    internal val playersFile = File(statusDir, "players.yml")
    internal val lobbyFile = File(folder, "lobby.yml")

    init {
        folder.mkdirs()
        arenaDir.mkdirs()
        statusDir.mkdirs()
        statsDir.mkdirs()
    }

    internal fun arenaFile(name: String) = File(arenaDir, "$name.yml")
    internal fun statusFile(name: String) = File(statusDir, "$name.yml")
    internal fun statsFile(uuid: Uuid) = File(statsDir, "$uuid.yml")

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
            tmp.delete()
            logger.warning("Failed to save ${file.path}: ${e.message}")
            throw PersistenceFailure("Could not save YAML file: ${file.path}", e)
        }
    }

    // ---- コーデック --------------------------------------------------------

    internal fun readLocation(yaml: YamlConfiguration, path: String): WorldPosition? {
        val world = yaml.getString("$path.world") ?: return null
        return try {
            WorldPosition.new(
                world = world,
                x = yaml.getDouble("$path.x"),
                y = yaml.getDouble("$path.y"),
                z = yaml.getDouble("$path.z"),
                yaw = yaml.getDouble("$path.yaw").toFloat(),
                pitch = yaml.getDouble("$path.pitch").toFloat()
            )
        } catch (e: IllegalArgumentException) {
            throw PersistenceFailure("Invalid location at $path", e)
        }
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
}
