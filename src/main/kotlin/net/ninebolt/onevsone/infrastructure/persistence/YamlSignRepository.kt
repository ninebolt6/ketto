package net.ninebolt.onevsone.infrastructure.persistence

import net.ninebolt.onevsone.application.port.ArenaSignRepository
import net.ninebolt.onevsone.application.port.PersistenceFailure
import net.ninebolt.onevsone.domain.BlockPosition
import org.bukkit.configuration.file.YamlConfiguration

/**
 * Persistence for the sign section of arena/<name>.yml.
 * The position -> arena-name reverse lookup uses an in-memory index built from
 * the arena directory on first access, avoiding a disk scan per sign click.
 * The index is kept in sync by setSign/clearSign. Manual edits made while
 * running are not reflected.
 */
class YamlSignRepository(private val store: YamlStore) : ArenaSignRepository {

    private val index: MutableMap<BlockPosition, String> by lazy { scan() }

    private fun scan(): MutableMap<BlockPosition, String> = mutableMapOf<BlockPosition, String>().apply {
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

    private fun signPos(yaml: YamlConfiguration): BlockPosition? {
        val world = yaml.getString("sign.world")?.takeIf { it.isNotBlank() } ?: return null
        if (!yaml.contains("sign.x")) return null
        // Files written by older versions hold fractional coordinates; they truncate to the block
        return BlockPosition.new(
            world,
            yaml.getDouble("sign.x").toInt(),
            yaml.getDouble("sign.y").toInt(),
            yaml.getDouble("sign.z").toInt()
        )
    }

    override fun signLocation(arenaName: String): BlockPosition? =
        index.entries.firstOrNull { it.value == arenaName }?.key

    override fun setSign(arenaName: String, position: BlockPosition) {
        store.update(store.arenaFile(arenaName)) { yaml ->
            yaml.set("sign", null)
            yaml.set("sign.world", position.world)
            yaml.set("sign.x", position.x)
            yaml.set("sign.y", position.y)
            yaml.set("sign.z", position.z)
        }
        index.values.remove(arenaName)
        index[position] = arenaName
    }

    override fun clearSign(arenaName: String) {
        store.updateIf(store.arenaFile(arenaName)) { yaml ->
            yaml.isConfigurationSection("sign").also { if (it) yaml.set("sign", null) }
        }
        index.entries.removeAll { it.value == arenaName }
    }

    override fun signOwner(position: BlockPosition): String? = index[position]
}
