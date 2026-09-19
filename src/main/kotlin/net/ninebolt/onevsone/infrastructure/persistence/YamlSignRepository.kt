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

    private data class SignPos(val world: String, val x: Double, val y: Double, val z: Double)

    private var index: MutableMap<SignPos, String>? = null

    private fun indexOrScan(): MutableMap<SignPos, String> =
        index ?: scan().also { index = it }

    private fun scan(): MutableMap<SignPos, String> {
        val found = mutableMapOf<SignPos, String>()
        store.arenaDir.listFiles()?.forEach { file ->
            if (!file.isFile || file.extension != "yml") return@forEach
            val pos = try {
                signPos(store.load(file))
            } catch (e: PersistenceFailure) {
                store.warn("Skipping unreadable arena file ${file.name} for sign index")
                null
            } ?: return@forEach
            found[pos] = file.nameWithoutExtension
        }
        return found
    }

    private fun signPos(yaml: YamlConfiguration): SignPos? {
        val world = yaml.getString("sign.world") ?: return null
        if (!yaml.contains("sign.x")) return null
        return SignPos(world, yaml.getDouble("sign.x"), yaml.getDouble("sign.y"), yaml.getDouble("sign.z"))
    }

    override fun signLocation(arenaName: String): WorldPosition? =
        indexOrScan().entries.firstOrNull { it.value == arenaName }
            ?.let { (pos) -> WorldPosition(pos.world, pos.x, pos.y, pos.z) }

    override fun setSign(arenaName: String, position: WorldPosition) {
        val file = store.arenaFile(arenaName)
        val yaml = store.load(file)
        store.writeLocation(yaml, "sign", position)
        store.save(yaml, file)
        indexOrScan().values.remove(arenaName)
        indexOrScan()[SignPos(position.world, position.x, position.y, position.z)] = arenaName
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
        indexOrScan().entries.removeIf { it.value == arenaName }
    }

    override fun signOwner(world: String, x: Double, y: Double, z: Double): String? =
        indexOrScan()[SignPos(world, x, y, z)]
}
