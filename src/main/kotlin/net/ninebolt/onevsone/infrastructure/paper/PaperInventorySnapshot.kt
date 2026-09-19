package net.ninebolt.onevsone.infrastructure.paper

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory

/**
 * ItemStack ベースのインベントリスナップショット。
 * 複製・防具 4 slot・offhand(41 slot 目)・AIR 判定を維持する。
 * この型は infrastructure 内に閉じ込め、内部層には出さない。
 */
data class PaperInventorySnapshot(
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
        fun capture(inventory: PlayerInventory): PaperInventorySnapshot = PaperInventorySnapshot(
            armor = inventory.armorContents.map { it?.clone() },
            items = inventory.contents.map { it?.clone() }
        )
    }
}
