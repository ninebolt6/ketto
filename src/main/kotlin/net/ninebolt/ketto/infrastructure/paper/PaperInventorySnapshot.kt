package net.ninebolt.ketto.infrastructure.paper

import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory

data class PaperInventorySnapshot(
    val armor: List<ItemStack?> = emptyList(),
    val items: List<ItemStack?> = emptyList(),
) {
    fun apply(inventory: PlayerInventory) {
        inventory.clear()
        inventory.contents = Array(inventory.contents.size) { items.getOrNull(it)?.clone() }
        inventory.armorContents = Array(4) { armor.getOrNull(it)?.clone() }
    }

    companion object {
        fun capture(inventory: PlayerInventory): PaperInventorySnapshot = PaperInventorySnapshot(
            armor = inventory.armorContents.map { it?.clone() },
            items = inventory.contents.map { it?.clone() },
        )
    }
}
