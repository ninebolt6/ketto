package net.ninebolt.onevsone.infrastructure.persistence

import net.ninebolt.onevsone.application.port.ArenaSignRepository
import net.ninebolt.onevsone.domain.BlockPosition

/**
 * Sign registrations in the arena_signs table (arena-owned: deleted by the
 * arena's cascade). The position -> arena-name reverse lookup keeps an
 * in-memory index loaded on first access so sign clicks and break checks do
 * not query per event; setSign/clearSign keep it in sync.
 */
class SqliteArenaSignRepository(private val store: SqliteStore) : ArenaSignRepository {

    private val index: MutableMap<BlockPosition, String> by lazy { scan() }

    private fun scan(): MutableMap<BlockPosition, String> =
        store.query("SELECT arena_name, world, x, y, z FROM arena_signs") { row ->
            BlockPosition.new(row.getString("world"), row.getInt("x"), row.getInt("y"), row.getInt("z")) to
                row.getString("arena_name")
        }.toMap().toMutableMap()

    override fun signLocation(arenaName: String): BlockPosition? =
        index.entries.firstOrNull { it.value == arenaName }?.key

    override fun setSign(arenaName: String, position: BlockPosition) {
        store.exec(
            "INSERT OR REPLACE INTO arena_signs(arena_name, world, x, y, z) VALUES (?, ?, ?, ?, ?)",
            arenaName, position.world, position.x, position.y, position.z
        )
        index.values.remove(arenaName)
        index[position] = arenaName
    }

    override fun clearSign(arenaName: String) {
        store.exec("DELETE FROM arena_signs WHERE arena_name = ?", arenaName)
        index.entries.removeAll { it.value == arenaName }
    }

    override fun signOwner(position: BlockPosition): String? = index[position]
}
