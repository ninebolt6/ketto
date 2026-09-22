package net.ninebolt.onevsone.infrastructure.persistence

import net.ninebolt.onevsone.infrastructure.paper.PaperInventorySnapshot

/**
 * Persistence for the inventory section (arena kit) of arena/<name>.yml.
 * enabled/spawn in the same file are handled by YamlArenaRepository, sign by
 * YamlSignRepository.
 */
class YamlKitStore(private val store: YamlStore) {

    fun loadArenaKit(arenaName: String): PaperInventorySnapshot =
        store.readSnapshot(store.load(store.arenaFile(arenaName)), "inventory")

    fun saveArenaKit(arenaName: String, kit: PaperInventorySnapshot) {
        store.update(store.arenaFile(arenaName)) { yaml ->
            store.writeSnapshot(yaml, "inventory", kit)
        }
    }
}
