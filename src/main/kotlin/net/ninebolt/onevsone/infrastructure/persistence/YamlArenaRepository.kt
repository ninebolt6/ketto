package net.ninebolt.onevsone.infrastructure.persistence

import net.ninebolt.onevsone.application.port.ArenaRepository
import net.ninebolt.onevsone.domain.ArenaDefinition
import net.ninebolt.onevsone.domain.ArenaId
import net.ninebolt.onevsone.domain.WorldPosition
import net.ninebolt.onevsone.domain.isValidArenaName

/** arenalist.yml・arena/<name>.yml・config.yml(ロビー/看板)の永続化。 */
class YamlArenaRepository(private val store: YamlStore) : ArenaRepository {

    override fun arenaNames(): List<String> =
        store.load(store.arenaListFile).getStringList("arenas")

    override fun saveArenaNames(names: List<String>) {
        val yaml = store.load(store.arenaListFile)
        yaml.set("arenas", names)
        store.save(yaml, store.arenaListFile)
    }

    override fun find(name: String): ArenaDefinition? {
        if (!isValidArenaName(name)) return null
        val cfg = store.load(store.arenaFile(name))
        return ArenaDefinition(
            id = ArenaId(name),
            enabled = cfg.getBoolean("enabled", false),
            spawn1 = store.readLocation(cfg, "spawn1"),
            spawn2 = store.readLocation(cfg, "spawn2")
        )
    }

    /** enabled とスポーンのみを保存。inventory セクションは装備側の責務なので保持する。 */
    override fun save(arena: ArenaDefinition) {
        val file = store.arenaFile(arena.name)
        val yaml = store.load(file)
        yaml.set("enabled", arena.enabled)
        arena.spawn1?.let { store.writeLocation(yaml, "spawn1", it) }
        arena.spawn2?.let { store.writeLocation(yaml, "spawn2", it) }
        store.save(yaml, file)
    }

    override fun delete(name: String) {
        store.arenaFile(name).delete()
        store.statusFile(name).delete()
    }

    override fun lobby(): WorldPosition? {
        val cfg = store.config()
        val world = cfg.getString("world") ?: return null
        return WorldPosition(
            world = world,
            x = cfg.getDouble("x"),
            y = cfg.getDouble("y"),
            z = cfg.getDouble("z"),
            yaw = cfg.getDouble("yaw").toFloat(),
            pitch = cfg.getDouble("pitch").toFloat()
        )
    }

    override fun setLobby(position: WorldPosition) {
        val cfg = store.config()
        cfg.set("world", position.world)
        cfg.set("x", position.x)
        cfg.set("y", position.y)
        cfg.set("z", position.z)
        cfg.set("yaw", position.yaw.toDouble())
        cfg.set("pitch", position.pitch.toDouble())
        store.saveConfig()
    }

    override fun signLocation(arenaName: String): WorldPosition? {
        val cfg = store.config()
        val world = cfg.getString("sign.$arenaName.world") ?: return null
        if (!cfg.contains("sign.$arenaName.x")) return null
        return WorldPosition(
            world = world,
            x = cfg.getDouble("sign.$arenaName.x"),
            y = cfg.getDouble("sign.$arenaName.y"),
            z = cfg.getDouble("sign.$arenaName.z")
        )
    }

    override fun setSign(arenaName: String, position: WorldPosition) {
        val cfg = store.config()
        store.writeLocation(cfg, "sign.$arenaName", position)
        store.saveConfig()
    }

    override fun clearSign(arenaName: String) {
        val cfg = store.config()
        cfg.set("sign.$arenaName", null)
        store.saveConfig()
    }

    override fun signOwner(world: String, x: Double, y: Double, z: Double): String? {
        val cfg = store.config()
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
