package net.ninebolt.onevsone

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

class YamlStore(private val folder: File, private val logger: Logger) {

    private val arenaDir = File(folder, "arena")
    private val statusDir = File(folder, "status")
    private val statsDir = File(folder, "stats")
    private val arenaListFile = File(folder, "arenalist.yml")
    private val playersFile = File(statusDir, "players.yml")
    private val configFile = File(folder, "config.yml")

    private var config: YamlConfiguration? = null

    init {
        folder.mkdirs()
        arenaDir.mkdirs()
        statusDir.mkdirs()
        statsDir.mkdirs()
    }

    fun isValidArenaName(name: String): Boolean =
        name.isNotBlank() &&
            name.length <= 64 &&
            name.none { it == '/' || it == '\\' || it == '.' || it.isISOControl() } &&
            !name.equals("players", ignoreCase = true)

    private fun loadYaml(file: File): YamlConfiguration {
        if (!file.exists()) return YamlConfiguration()
        val yaml = YamlConfiguration()
        try {
            yaml.load(file)
        } catch (e: InvalidConfigurationException) {
            logger.warning("Failed to parse ${file.path}: ${e.message}")
            throw IllegalStateException("Unreadable YAML file: ${file.path}", e)
        } catch (e: IOException) {
            logger.warning("Failed to read ${file.path}: ${e.message}")
            throw IllegalStateException("Unreadable YAML file: ${file.path}", e)
        }
        return yaml
    }

    private fun saveYaml(yaml: YamlConfiguration, file: File) {
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
            throw IllegalStateException("Could not save YAML file: ${file.path}", e)
        }
    }

    private fun config(): YamlConfiguration {
        config?.let { return it }
        val loaded = loadYaml(configFile)
        config = loaded
        return loaded
    }

    private fun saveConfig() = saveYaml(config(), configFile)

    fun arenaNames(): List<String> = loadYaml(arenaListFile).getStringList("arenas")

    fun saveArenaNames(names: List<String>) {
        val yaml = loadYaml(arenaListFile)
        yaml.set("arenas", names)
        saveYaml(yaml, arenaListFile)
    }

    private fun arenaFile(name: String) = File(arenaDir, "$name.yml")

    private fun readLocation(yaml: YamlConfiguration, path: String): SavedLocation? {
        val world = yaml.getString("$path.world") ?: return null
        return SavedLocation(
            world = world,
            x = yaml.getDouble("$path.x"),
            y = yaml.getDouble("$path.y"),
            z = yaml.getDouble("$path.z"),
            yaw = yaml.getDouble("$path.yaw").toFloat(),
            pitch = yaml.getDouble("$path.pitch").toFloat()
        )
    }

    private fun writeLocation(yaml: YamlConfiguration, path: String, loc: SavedLocation) {
        yaml.set("$path.world", loc.world)
        yaml.set("$path.x", loc.x)
        yaml.set("$path.y", loc.y)
        yaml.set("$path.z", loc.z)
        yaml.set("$path.yaw", loc.yaw.toDouble())
        yaml.set("$path.pitch", loc.pitch.toDouble())
    }

    private fun readSnapshot(yaml: YamlConfiguration, path: String): InventorySnapshot {
        val armor = (yaml.getList("$path.armor") ?: emptyList()).map { it as? ItemStack }
        val items = (yaml.getList("$path.item") ?: emptyList()).map { it as? ItemStack }
        return InventorySnapshot(armor, items)
    }

    private fun writeSnapshot(yaml: YamlConfiguration, path: String, snapshot: InventorySnapshot) {
        yaml.set("$path.armor", snapshot.armor)
        yaml.set("$path.item", snapshot.items)
    }

    fun loadArena(name: String): Arena? {
        if (!isValidArenaName(name)) return null
        val cfg = loadYaml(arenaFile(name))
        val arena = Arena(name)
        arena.enabled = cfg.getBoolean("enabled", false)
        arena.spawn1 = readLocation(cfg, "spawn1")
        arena.spawn2 = readLocation(cfg, "spawn2")
        arena.kit = readSnapshot(cfg, "inventory")
        return arena
    }

    fun saveArena(arena: Arena) {
        val yaml = loadYaml(arenaFile(arena.name))
        yaml.set("enabled", arena.enabled)
        arena.spawn1?.let { writeLocation(yaml, "spawn1", it) }
        arena.spawn2?.let { writeLocation(yaml, "spawn2", it) }
        writeSnapshot(yaml, "inventory", arena.kit)
        saveYaml(yaml, arenaFile(arena.name))
    }

    fun deleteArena(name: String) {
        arenaFile(name).delete()
        File(statusDir, "$name.yml").delete()
    }

    private fun statusFile(name: String) = File(statusDir, "$name.yml")

    fun saveStatus(arena: Arena) {
        val yaml = YamlConfiguration()
        yaml.set("status", arena.state.name)
        yaml.set("players", arena.players.map { it.name })
        val winMap = mutableMapOf<String, Int>()
        for ((id, wins) in arena.wins) {
            val name = arena.players.firstOrNull { it.id == id }?.name ?: continue
            winMap[name] = wins
        }
        yaml.set("win", winMap)
        saveYaml(yaml, statusFile(arena.name))
    }

    fun registerParticipant(participant: Participant, arenaName: String) {
        val yaml = loadYaml(playersFile)
        val players = yaml.getStringList("players")
        if (!players.contains(participant.name)) players.add(participant.name)
        yaml.set("players", players)
        yaml.set("arena.${participant.name}", arenaName)
        writeSnapshot(yaml, "inv.${participant.name}", participant.snapshot)
        yaml.set("inv.${participant.name}.uuid", participant.id.toString())
        saveYaml(yaml, playersFile)
    }

    fun unregisterParticipant(name: String, discardSnapshot: Boolean = true) {
        val yaml = loadYaml(playersFile)
        val players = yaml.getStringList("players")
        players.remove(name)
        yaml.set("players", players)
        yaml.set("arena.$name", null)
        if (discardSnapshot) yaml.set("inv.$name", null)
        saveYaml(yaml, playersFile)
    }

    fun discardPendingRestore(name: String) {
        val yaml = loadYaml(playersFile)
        if (!yaml.isConfigurationSection("inv.$name")) return
        yaml.set("inv.$name", null)
        saveYaml(yaml, playersFile)
    }

    fun pendingRestores(): List<PendingRestore> {
        val yaml = loadYaml(playersFile)
        val inv = yaml.getConfigurationSection("inv") ?: return emptyList()
        val result = mutableListOf<PendingRestore>()
        for (name in inv.getKeys(false)) {
            val snapshot = readSnapshot(yaml, "inv.$name")
            val uuid = yaml.getString("inv.$name.uuid")?.let {
                try { UUID.fromString(it) } catch (e: IllegalArgumentException) { null }
            }
            result.add(PendingRestore(name, uuid, snapshot))
        }
        return result
    }

    fun clearRegistrations() {
        val yaml = loadYaml(playersFile)
        yaml.set("players", emptyList<String>())
        yaml.set("arena", null)
        saveYaml(yaml, playersFile)
    }

    private fun statsFile(uuid: UUID) = File(statsDir, "$uuid.yml")

    fun statsExist(uuid: UUID): Boolean = statsFile(uuid).exists()

    fun readStats(uuid: UUID): Pair<Int, Int> {
        val yaml = loadYaml(statsFile(uuid))
        return yaml.getInt("win") to yaml.getInt("lose")
    }

    private fun writeStats(uuid: UUID, win: Int, lose: Int) {
        val file = statsFile(uuid)
        val yaml = loadYaml(file)
        yaml.set("win", win)
        yaml.set("lose", lose)
        saveYaml(yaml, file)
    }

    fun addWin(uuid: UUID) {
        val (win, lose) = readStats(uuid)
        writeStats(uuid, win + 1, lose)
    }

    fun addLose(uuid: UUID) {
        val (win, lose) = readStats(uuid)
        writeStats(uuid, win, lose + 1)
    }

    fun lobby(): SavedLocation? {
        val cfg = config()
        val world = cfg.getString("world") ?: return null
        return SavedLocation(
            world = world,
            x = cfg.getDouble("x"),
            y = cfg.getDouble("y"),
            z = cfg.getDouble("z"),
            yaw = cfg.getDouble("yaw").toFloat(),
            pitch = cfg.getDouble("pitch").toFloat()
        )
    }

    fun setLobby(loc: SavedLocation) {
        val cfg = config()
        cfg.set("world", loc.world)
        cfg.set("x", loc.x)
        cfg.set("y", loc.y)
        cfg.set("z", loc.z)
        cfg.set("yaw", loc.yaw.toDouble())
        cfg.set("pitch", loc.pitch.toDouble())
        saveConfig()
    }

    fun signLocation(name: String): SavedLocation? {
        val cfg = config()
        val world = cfg.getString("sign.$name.world") ?: return null
        if (!cfg.contains("sign.$name.x")) return null
        return SavedLocation(
            world = world,
            x = cfg.getDouble("sign.$name.x"),
            y = cfg.getDouble("sign.$name.y"),
            z = cfg.getDouble("sign.$name.z")
        )
    }

    fun setSign(name: String, loc: SavedLocation) {
        val cfg = config()
        writeLocation(cfg, "sign.$name", loc)
        saveConfig()
    }

    fun clearSign(name: String) {
        val cfg = config()
        cfg.set("sign.$name", null)
        saveConfig()
    }

    fun signOwner(world: String, x: Double, y: Double, z: Double): String? {
        val cfg = config()
        val section = cfg.getConfigurationSection("sign") ?: return null
        for (name in section.getKeys(false)) {
            if (cfg.getString("sign.$name.world") == world &&
                cfg.getDouble("sign.$name.x") == x &&
                cfg.getDouble("sign.$name.y") == y &&
                cfg.getDouble("sign.$name.z") == z
            ) return name
        }
        return null
    }
}

data class PendingRestore(val name: String, val uuid: UUID?, val snapshot: InventorySnapshot)
