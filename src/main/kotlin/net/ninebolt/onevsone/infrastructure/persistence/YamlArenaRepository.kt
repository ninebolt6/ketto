package net.ninebolt.onevsone.infrastructure.persistence

import net.ninebolt.onevsone.application.port.ArenaRepository
import net.ninebolt.onevsone.application.port.PersistenceFailure
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.isValidArenaName
import java.util.Locale

/** arenalist.yml・arena/<name>.yml の永続化。arenalist は登録順の index として内部管理する。 */
class YamlArenaRepository(private val store: YamlStore) : ArenaRepository {

    override fun loadAll(): List<Arena> {
        val names = store.load(store.arenaListFile).getStringList("arenas")
        val seen = mutableSetOf<String>()
        return names.mapNotNull { name ->
            if (!isValidArenaName(name)) {
                store.warn("Ignoring invalid arena name '$name' in arenalist.yml")
                return@mapNotNull null
            }
            if (!seen.add(name.lowercase(Locale.ROOT))) {
                store.warn("Ignoring duplicate arena name '$name' in arenalist.yml")
                return@mapNotNull null
            }
            try {
                find(name)
            } catch (e: PersistenceFailure) {
                null
            } ?: run {
                store.warn("Arena '$name' could not be loaded; skipping")
                null
            }
        }
    }

    override fun find(name: String): Arena? {
        if (!isValidArenaName(name)) return null
        val cfg = store.load(store.arenaFile(name))
        return Arena(
            id = Arena.Id(name),
            enabled = cfg.getBoolean("enabled", false),
            spawn1 = store.readLocation(cfg, "spawn1"),
            spawn2 = store.readLocation(cfg, "spawn2")
        )
    }

    /** enabled とスポーンのみを保存。inventory(装備)・sign(看板) セクションは各責務側が持つので保持する。 */
    override fun save(arena: Arena) {
        val file = store.arenaFile(arena.name)
        val yaml = store.load(file)
        yaml.set("enabled", arena.enabled)
        arena.spawn1?.let { store.writeLocation(yaml, "spawn1", it) }
        arena.spawn2?.let { store.writeLocation(yaml, "spawn2", it) }
        store.save(yaml, file)
        registerName(arena.name)
    }

    override fun delete(name: String) {
        store.arenaFile(name).delete()
        store.statusFile(name).delete()
        unregisterName(name)
    }

    private fun registerName(name: String) {
        val yaml = store.load(store.arenaListFile)
        val names = yaml.getStringList("arenas")
        if (names.any { it.equals(name, ignoreCase = true) }) return
        yaml.set("arenas", names + name)
        store.save(yaml, store.arenaListFile)
    }

    private fun unregisterName(name: String) {
        val yaml = store.load(store.arenaListFile)
        val names = yaml.getStringList("arenas")
        val remaining = names.filterNot { it.equals(name, ignoreCase = true) }
        if (remaining.size == names.size) return
        yaml.set("arenas", remaining)
        store.save(yaml, store.arenaListFile)
    }
}
