package net.ninebolt.onevsone.infrastructure.persistence

import net.ninebolt.onevsone.infrastructure.paper.PaperInventorySnapshot

/**
 * Arena kit payloads in the arena_kits table (arena-owned: deleted by the
 * arena's cascade). A missing row is an empty kit.
 */
class SqliteKitStore(private val store: SqliteStore) {

    fun loadArenaKit(arenaName: String): PaperInventorySnapshot =
        store.queryOne("SELECT payload FROM arena_kits WHERE arena_name = ?", arenaName) { row ->
            store.decodeSnapshot(row.getString("payload"))
        } ?: PaperInventorySnapshot()

    fun saveArenaKit(arenaName: String, kit: PaperInventorySnapshot) {
        store.exec(
            "INSERT OR REPLACE INTO arena_kits(arena_name, payload) VALUES (?, ?)",
            arenaName, store.encodeSnapshot(kit)
        )
    }

    /** Redundant with the arena's cascade, but harmless for explicit cleanup. */
    fun deleteArenaKit(arenaName: String) {
        store.exec("DELETE FROM arena_kits WHERE arena_name = ?", arenaName)
    }
}
