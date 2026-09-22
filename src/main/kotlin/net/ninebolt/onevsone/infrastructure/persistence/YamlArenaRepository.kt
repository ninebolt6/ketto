package net.ninebolt.onevsone.infrastructure.persistence

import net.ninebolt.onevsone.application.port.ArenaRepository
import net.ninebolt.onevsone.application.port.PersistenceFailure
import net.ninebolt.onevsone.domain.Arena
import java.util.Locale

/** Persistence for arenalist.yml and arena/<name>.yml. arenalist is managed internally as a registration-order index. */
class YamlArenaRepository(private val store: YamlStore) : ArenaRepository {

    override fun loadAll(): List<Arena> {
        val names = store.load(store.arenaListFile).getStringList("arenas")
        val seen = mutableSetOf<String>()
        return names.mapNotNull { name ->
            if (Arena.Id.of(name) == null) {
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
        val id = Arena.Id.of(name) ?: return null
        val cfg = store.load(store.arenaFile(name))
        return Arena.new(
            id = id,
            enabled = cfg.getBoolean("enabled", false),
            spawn1 = store.readLocation(cfg, "spawn1"),
            spawn2 = store.readLocation(cfg, "spawn2")
        )
    }

    /** Saves only enabled and spawns; the kit and sign sections belong to YamlKitStore / YamlSignRepository. */
    override fun save(arena: Arena) {
        val file = store.arenaFile(arena.name)
        store.update(file) { yaml ->
            yaml.set("enabled", arena.enabled)
            arena.spawn1?.let { store.writeLocation(yaml, "spawn1", it) }
            arena.spawn2?.let { store.writeLocation(yaml, "spawn2", it) }
        }
        registerName(arena.name)
    }

    override fun delete(name: String) {
        store.arenaFile(name).delete()
        store.statusFile(name).delete()
        unregisterName(name)
    }

    private fun registerName(name: String) {
        store.updateIf(store.arenaListFile) { yaml ->
            val names = yaml.getStringList("arenas")
            if (names.any { it.equals(name, ignoreCase = true) }) {
                false
            } else {
                yaml.set("arenas", names + name)
                true
            }
        }
    }

    private fun unregisterName(name: String) {
        store.updateIf(store.arenaListFile) { yaml ->
            val names = yaml.getStringList("arenas")
            val remaining = names.filterNot { it.equals(name, ignoreCase = true) }
            if (remaining.size == names.size) {
                false
            } else {
                yaml.set("arenas", remaining)
                true
            }
        }
    }
}
