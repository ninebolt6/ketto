package net.ninebolt.onevsone.infrastructure.persistence

import net.ninebolt.onevsone.infrastructure.paper.PaperInventorySnapshot

/**
 * arena/<name>.yml の inventory セクション(アリーナ装備)の永続化。
 * 同一ファイルの enabled/spawn は YamlArenaRepository、sign は YamlSignRepository が担う。
 */
class YamlKitStore(private val store: YamlStore) {

    fun loadArenaKit(arenaName: String): PaperInventorySnapshot =
        store.readSnapshot(store.load(store.arenaFile(arenaName)), "inventory")

    fun saveArenaKit(arenaName: String, kit: PaperInventorySnapshot) {
        val file = store.arenaFile(arenaName)
        val yaml = store.load(file)
        store.writeSnapshot(yaml, "inventory", kit)
        store.save(yaml, file)
    }
}
