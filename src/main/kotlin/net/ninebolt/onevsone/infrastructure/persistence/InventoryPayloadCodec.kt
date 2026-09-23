package net.ninebolt.onevsone.infrastructure.persistence

import net.ninebolt.onevsone.application.port.PersistenceFailure
import net.ninebolt.onevsone.infrastructure.paper.PaperInventorySnapshot
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ItemStack

/**
 * Serialization of inventory payloads for the payload TEXT columns. Stored as
 * a YAML string so ItemStack codecs stay on Bukkit's serializer; the column
 * itself never exposes ItemStack types.
 */
internal object InventoryPayloadCodec {

    fun encode(snapshot: PaperInventorySnapshot): String {
        val yaml = YamlConfiguration()
        yaml.set("armor", snapshot.armor)
        yaml.set("item", snapshot.items)
        return yaml.saveToString()
    }

    /** Stored payloads are untrusted data: any decode problem is a PersistenceFailure. */
    fun decode(payload: String): PaperInventorySnapshot {
        try {
            val yaml = YamlConfiguration()
            yaml.loadFromString(payload)
            val armor = (yaml.getList("armor") ?: emptyList()).map { it as? ItemStack }
            val items = (yaml.getList("item") ?: emptyList()).map { it as? ItemStack }
            return PaperInventorySnapshot(armor, items)
        } catch (e: Exception) {
            throw PersistenceFailure("Could not decode inventory payload", e)
        }
    }
}
