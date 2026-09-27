package net.ninebolt.onevsone.infrastructure.persistence

import net.ninebolt.onevsone.infrastructure.paper.PaperInventorySnapshot

// rows are deleted by the arena's cascade
class SqliteKitStore(private val store: SqliteStore) {

    fun loadArenaKit(arenaName: String): PaperInventorySnapshot = store.queryOne("SELECT payload FROM arena_kits WHERE arena_name = ?", arenaName) { row ->
        InventoryPayloadCodec.decode(row.getString("payload"))
    } ?: PaperInventorySnapshot()

    fun saveArenaKit(arenaName: String, kit: PaperInventorySnapshot) {
        store.exec(
            "INSERT OR REPLACE INTO arena_kits(arena_name, payload) VALUES (?, ?)",
            arenaName,
            InventoryPayloadCodec.encode(kit),
        )
    }
}
