package net.ninebolt.onevsone.infrastructure.persistence

import net.ninebolt.onevsone.application.port.ArenaRepository
import net.ninebolt.onevsone.application.port.PersistenceFailure
import net.ninebolt.onevsone.domain.ArenaDefinition
import net.ninebolt.onevsone.domain.ArenaId
import net.ninebolt.onevsone.domain.isValidArenaName
import java.util.Locale

/** arenalist.yml・arena/<name>.yml の永続化。arenalist は登録順の index として内部管理する。 */
class YamlArenaRepository(private val store: YamlStore) : ArenaRepository {

    override fun loadAll(): List<ArenaDefinition> {
        val names = store.load(store.arenaListFile).getStringList("arenas")
        val seen = mutableSetOf<String>()
        val definitions = mutableListOf<ArenaDefinition>()
        names.forEach { name ->
            if (!isValidArenaName(name)) {
                store.warn("Ignoring invalid arena name '$name' in arenalist.yml")
                return@forEach
            }
            if (!seen.add(name.lowercase(Locale.ROOT))) {
                store.warn("Ignoring duplicate arena name '$name' in arenalist.yml")
                return@forEach
            }
            val definition = try {
                find(name)
            } catch (e: PersistenceFailure) {
                null
            }
            if (definition == null) {
                store.warn("Arena '$name' could not be loaded; skipping")
                return@forEach
            }
            definitions += definition
        }
        return definitions
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
