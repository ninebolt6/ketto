package net.ninebolt.ketto.infrastructure.persistence

import net.ninebolt.ketto.application.port.PersistenceException
import net.ninebolt.ketto.infrastructure.paper.PaperInventorySnapshot
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ItemStack

internal object InventoryPayloadCodec {

    fun encode(snapshot: PaperInventorySnapshot): String {
        val yaml = YamlConfiguration()
        writeSnapshot(yaml, snapshot)
        return yaml.saveToString()
    }

    // stored payloads are untrusted: any decode problem exits as PersistenceException
    fun decode(payload: String): PaperInventorySnapshot {
        try {
            return readSnapshot(load(payload))
        } catch (e: Exception) {
            throw PersistenceException("Could not decode inventory payload", e)
        }
    }

    internal fun load(payload: String): YamlConfiguration {
        val yaml = YamlConfiguration()
        yaml.loadFromString(payload)
        return yaml
    }

    internal fun writeSnapshot(yaml: YamlConfiguration, snapshot: PaperInventorySnapshot) {
        yaml.set("armor", snapshot.armor)
        yaml.set("item", snapshot.items)
    }

    internal fun readSnapshot(yaml: YamlConfiguration): PaperInventorySnapshot = PaperInventorySnapshot(decodeSlots(yaml.getList("armor")), decodeSlots(yaml.getList("item")))

    private fun decodeSlots(entries: List<*>?): List<ItemStack?> = entries.orEmpty().map { entry ->
        when (entry) {
            null -> null
            is ItemStack -> entry
            else -> throw IllegalArgumentException("Unexpected inventory payload entry: $entry")
        }
    }
}
