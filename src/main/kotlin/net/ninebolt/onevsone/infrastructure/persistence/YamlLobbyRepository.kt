package net.ninebolt.onevsone.infrastructure.persistence

import net.ninebolt.onevsone.application.port.LobbyRepository
import net.ninebolt.onevsone.domain.WorldPosition

/** config.yml のロビー座標の永続化。 */
class YamlLobbyRepository(private val store: YamlStore) : LobbyRepository {

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
}
