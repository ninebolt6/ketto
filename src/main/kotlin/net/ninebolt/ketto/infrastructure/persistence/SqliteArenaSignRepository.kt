package net.ninebolt.ketto.infrastructure.persistence

import net.ninebolt.ketto.application.port.ArenaSignRepository
import net.ninebolt.ketto.domain.Arena
import net.ninebolt.ketto.domain.BlockPosition
import java.util.logging.Logger

// rows are deleted by the arena's cascade
// the in-memory index avoids a query per sign-click event; setSign/clearSign keep it in sync
class SqliteArenaSignRepository(
    private val store: SqliteStore,
    private val logger: Logger = Logger.getLogger(SqliteArenaSignRepository::class.java.name),
) : ArenaSignRepository {

    private val index: MutableMap<BlockPosition, Arena.Id> by lazy { scan() }

    private fun scan(): MutableMap<BlockPosition, Arena.Id> = store.query("SELECT arena_name, world, x, y, z FROM arena_signs") { row ->
        row.getString("arena_name") to BlockPosition.new(row.getString("world"), row.getInt("x"), row.getInt("y"), row.getInt("z"))
    }.mapNotNull { (name, position) ->
        val id = Arena.Id.of(name)
        if (id == null) {
            logger.warning("Ignoring sign for invalid arena name '$name' in arena_signs table")
            null
        } else {
            position to id
        }
    }.toMap().toMutableMap()

    override fun findSignLocation(arena: Arena.Id): BlockPosition? = index.entries.firstOrNull { it.value == arena }?.key

    override fun setSign(arena: Arena.Id, position: BlockPosition) {
        store.exec(
            "INSERT OR REPLACE INTO arena_signs(arena_name, world, x, y, z) VALUES (?, ?, ?, ?, ?)",
            arena.name,
            position.world,
            position.x,
            position.y,
            position.z,
        )
        index.values.remove(arena)
        index[position] = arena
    }

    override fun clearSign(arena: Arena.Id) {
        store.exec("DELETE FROM arena_signs WHERE arena_name = ?", arena.name)
        index.entries.removeAll { it.value == arena }
    }

    override fun findSignOwner(position: BlockPosition): Arena.Id? = index[position]
}
