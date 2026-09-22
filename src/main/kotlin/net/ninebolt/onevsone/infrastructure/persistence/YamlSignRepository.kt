package net.ninebolt.onevsone.infrastructure.persistence

import net.ninebolt.onevsone.application.port.ArenaSignRepository
import net.ninebolt.onevsone.application.port.PersistenceFailure
import net.ninebolt.onevsone.domain.WorldPosition
import org.bukkit.configuration.file.YamlConfiguration

/**
 * Persistence for the sign section of arena/<name>.yml.
 * The position -> arena-name reverse lookup uses an in-memory index built from
 * the arena directory on first access, avoiding a disk scan per sign click.
 * The index is kept in sync by setSign/clearSign. Manual edits made while
 * running are not reflected.
 */
class YamlSignRepository(private val store: YamlStore) : ArenaSignRepository {

    private data class SignPos(val world: String, val x: Int, val y: Int, val z: Int)

    private val index: MutableMap<SignPos, String> by lazy { scan() }

    private fun scan(): MutableMap<SignPos, String> = mutableMapOf<SignPos, String>().apply {
        store.arenaDir.listFiles()?.forEach { file ->
            if (!file.isFile || file.extension != "yml") return@forEach
            val pos = try {
                signPos(store.load(file))
            } catch (e: PersistenceFailure) {
                store.warn("Skipping unreadable arena file ${file.name} for sign index")
                null
            } ?: return@forEach
            put(pos, file.nameWithoutExtension)
        }
    }

    private fun signPos(yaml: YamlConfiguration): SignPos? {
        val world = yaml.getString("sign.world")?.takeIf { it.isNotBlank() } ?: return null
        if (!yaml.contains("sign.x")) return null
        return SignPos(
            world,
            yaml.getDouble("sign.x").toInt(),
            yaml.getDouble("sign.y").toInt(),
            yaml.getDouble("sign.z").toInt()
        )
    }

    override fun signLocation(arenaName: String): WorldPosition? =
        index.entries.firstOrNull { it.value == arenaName }?.key
            ?.let { WorldPosition.new(it.world, it.x.toDouble(), it.y.toDouble(), it.z.toDouble()) }

    override fun setSign(arenaName: String, position: WorldPosition) {
        store.update(store.arenaFile(arenaName)) { yaml ->
            store.writeLocation(yaml, "sign", position)
        }
        index.values.remove(arenaName)
        index[SignPos(position.world, position.x.toInt(), position.y.toInt(), position.z.toInt())] = arenaName
    }

    override fun clearSign(arenaName: String) {
        store.updateIf(store.arenaFile(arenaName)) { yaml ->
            yaml.isConfigurationSection("sign").also { if (it) yaml.set("sign", null) }
        }
        index.entries.removeAll { it.value == arenaName }
    }

    override fun signOwner(world: String, x: Int, y: Int, z: Int): String? =
        index[SignPos(world, x, y, z)]
}
