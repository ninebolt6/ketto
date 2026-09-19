package net.ninebolt.onevsone.infrastructure.persistence

import net.ninebolt.onevsone.application.port.ArenaSignRepository
import net.ninebolt.onevsone.domain.WorldPosition

/** config.yml の sign.<arena> セクションの永続化。 */
class YamlSignRepository(private val store: YamlStore) : ArenaSignRepository {

    override fun signLocation(arenaName: String): WorldPosition? {
        val cfg = store.loadConfig()
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
        val cfg = store.loadConfig()
        store.writeLocation(cfg, "sign.$arenaName", position)
        store.saveConfig(cfg)
    }

    override fun clearSign(arenaName: String) {
        val cfg = store.loadConfig()
        cfg.set("sign.$arenaName", null)
        store.saveConfig(cfg)
    }

    override fun signOwner(world: String, x: Double, y: Double, z: Double): String? {
        val cfg = store.loadConfig()
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
