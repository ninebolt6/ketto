package net.ninebolt.onevsone.infrastructure.paper

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
    fun apply(inventory: PlayerInventory) {
        inventory.clear()
        inventory.contents = Array(inventory.contents.size) { items.getOrNull(it)?.clone() }
        inventory.armorContents = Array(4) { armor.getOrNull(it)?.clone() }
    }

    companion object {
        fun capture(inventory: PlayerInventory): PaperInventorySnapshot = PaperInventorySnapshot(
            armor = inventory.armorContents.map { it?.clone() },
            items = inventory.contents.map { it?.clone() }
        )
    }
}
