package net.ninebolt.ketto.infrastructure.persistence

import net.ninebolt.ketto.application.port.LobbyRepository
import net.ninebolt.ketto.domain.WorldPosition

// the lobby is a single-row table (id = 1)
class SqliteLobbyRepository(private val store: SqliteStore) : LobbyRepository {

    override fun findLobby(): WorldPosition? = store.queryOne("SELECT world, x, y, z, yaw, pitch FROM lobby WHERE id = 1") { row ->
        WorldPosition.new(
            world = row.getString("world"),
            x = row.getDouble("x"),
            y = row.getDouble("y"),
            z = row.getDouble("z"),
            yaw = row.getDouble("yaw").toFloat(),
            pitch = row.getDouble("pitch").toFloat(),
        )
    }

    override fun setLobby(position: WorldPosition) {
        store.exec(
            "INSERT OR REPLACE INTO lobby(id, world, x, y, z, yaw, pitch) VALUES (1, ?, ?, ?, ?, ?, ?)",
            position.world,
            position.x,
            position.y,
            position.z,
            position.yaw,
            position.pitch,
        )
    }
}
