package net.ninebolt.onevsone

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory

data class InventorySnapshot(
    val armor: List<ItemStack?> = emptyList(),
    val items: List<ItemStack?> = emptyList()
) {
    val isEmpty: Boolean
        get() = armor.all { it.isNullOrAir() } && items.all { it.isNullOrAir() }

    fun apply(inventory: PlayerInventory) {
        inventory.clear()
        val contents = inventory.contents
        val restored = arrayOfNulls<ItemStack>(contents.size)
        for (i in restored.indices) {
            restored[i] = items.getOrNull(i)?.clone()
        }
        inventory.contents = restored
        val armorContents = arrayOfNulls<ItemStack>(4)
        for (i in armorContents.indices) {
            armorContents[i] = armor.getOrNull(i)?.clone()
        }
        inventory.armorContents = armorContents
    }

    private fun ItemStack?.isNullOrAir(): Boolean =
        this == null || type == Material.AIR || type == Material.CAVE_AIR || type == Material.VOID_AIR

    companion object {
        fun capture(inventory: PlayerInventory): InventorySnapshot = InventorySnapshot(
            armor = inventory.armorContents.map { it?.clone() },
            items = inventory.contents.map { it?.clone() }
        )
    }
}
