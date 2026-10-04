package net.ninebolt.onevsone.infrastructure.persistence

import net.ninebolt.onevsone.application.port.PersistenceException
import net.ninebolt.onevsone.infrastructure.paper.PaperInventorySnapshot
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ItemStack

internal object InventoryPayloadCodec {

    fun encode(snapshot: PaperInventorySnapshot): String {
        val yaml = YamlConfiguration()
        yaml.set("armor", snapshot.armor)
        yaml.set("item", snapshot.items)
        return yaml.saveToString()
    }

    // stored payloads are untrusted: any decode problem exits as PersistenceException
    fun decode(payload: String): PaperInventorySnapshot {
        try {
            val yaml = YamlConfiguration()
            yaml.loadFromString(payload)
            return PaperInventorySnapshot(decodeSlots(yaml.getList("armor")), decodeSlots(yaml.getList("item")))
        } catch (e: Exception) {
            throw PersistenceException("Could not decode inventory payload", e)
        }
    }

    private fun decodeSlots(entries: List<*>?): List<ItemStack?> = entries.orEmpty().map { entry ->
        when (entry) {
            null -> null
            is ItemStack -> entry
            else -> throw IllegalArgumentException("Unexpected inventory payload entry: $entry")
        }
    }
}
