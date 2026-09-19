package net.ninebolt.onevsone.infrastructure.persistence

import net.ninebolt.onevsone.application.port.ArenaRepository
import net.ninebolt.onevsone.domain.ArenaDefinition
import net.ninebolt.onevsone.domain.ArenaId
import net.ninebolt.onevsone.domain.isValidArenaName

/** arenalist.yml・arena/<name>.yml の永続化。 */
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
}
