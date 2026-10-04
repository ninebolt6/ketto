package net.ninebolt.onevsone.infrastructure.paper.fixtures

import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.CraftingInventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.Recipe
import org.mockbukkit.mockbukkit.inventory.InventoryMock

// MockBukkit has no player crafting grid, so this mirrors the vanilla menu layout: slot 0 is the result, 1..4 the matrix
class CraftingGridMock :
    InventoryMock(null, InventoryType.CRAFTING),
    CraftingInventory {

    override fun getMatrix(): Array<ItemStack?> = Array(MATRIX_SIZE) { getItem(it + 1) }

    override fun setMatrix(contents: Array<ItemStack?>) {
        repeat(MATRIX_SIZE) { setItem(it + 1, contents.getOrNull(it)) }
    }

    override fun getResult(): ItemStack? = getItem(0)

    override fun setResult(item: ItemStack?) {
        setItem(0, item)
    }

    override fun getRecipe(): Recipe? = null

    private companion object {
        const val MATRIX_SIZE = 4
    }
}
