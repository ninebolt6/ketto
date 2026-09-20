package net.ninebolt.onevsone.infrastructure.persistence

import net.ninebolt.onevsone.application.port.ArenaSignRepository
import net.ninebolt.onevsone.application.port.PersistenceFailure
import net.ninebolt.onevsone.domain.WorldPosition
import org.bukkit.configuration.file.YamlConfiguration

/**
 * arena/<name>.yml の sign セクションの永続化。
 * 座標→アリーナ名の逆引きは初回アクセス時に arena ディレクトリから構築する
 * メモリ index で行い、看板クリック毎のディスク走査を避ける。
 * index は setSign/clearSign で同期する。稼働中の手編集は反映されない。
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
        val world = yaml.getString("sign.world") ?: return null
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
            ?.let { WorldPosition(it.world, it.x.toDouble(), it.y.toDouble(), it.z.toDouble()) }

    override fun setSign(arenaName: String, position: WorldPosition) {
        val file = store.arenaFile(arenaName)
        val yaml = store.load(file)
        store.writeLocation(yaml, "sign", position)
        store.save(yaml, file)
        index.values.remove(arenaName)
        index[SignPos(position.world, position.x.toInt(), position.y.toInt(), position.z.toInt())] = arenaName
    }

    override fun clearSign(arenaName: String) {
        val file = store.arenaFile(arenaName)
        if (file.exists()) {
            val yaml = store.load(file)
            if (yaml.isConfigurationSection("sign")) {
                yaml.set("sign", null)
                store.save(yaml, file)
            }
        }
        index.entries.removeAll { it.value == arenaName }
    }

    override fun signOwner(world: String, x: Int, y: Int, z: Int): String? =
        index[SignPos(world, x, y, z)]
}
