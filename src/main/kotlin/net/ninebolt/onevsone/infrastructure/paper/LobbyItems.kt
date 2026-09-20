package net.ninebolt.onevsone.infrastructure.paper

import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/** バックアップ復元フォールバック用のロビーアイテム。内容とスロット配置をここに集約する。 */
class LobbyItems(private val messages: Messages) {
    fun give(player: Player) {
        val compass = ItemStack(Material.COMPASS)
        compass.editMeta { it.displayName(messages.render(messages.compassName)) }
        val feather = ItemStack(Material.FEATHER)
        feather.editMeta { it.displayName(messages.render(messages.featherName)) }
        player.inventory.setItem(0, compass)
        player.inventory.setItem(8, feather)
    }
}
