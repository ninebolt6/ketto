package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.application.ArenaApplicationService
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.block.Block
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDispenseArmorEvent
import org.bukkit.event.block.BlockFertilizeEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.SignChangeEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.EntityPlaceEvent
import org.bukkit.event.hanging.HangingPlaceEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryInteractEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerArmorStandManipulateEvent
import org.bukkit.event.player.PlayerAttemptPickupItemEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerBucketEntityEvent
import org.bukkit.event.player.PlayerBucketFillEvent
import org.bukkit.event.player.PlayerBucketFishEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerHarvestBlockEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerPickupArrowEvent
import org.bukkit.inventory.InventoryHolder

/**
 * 試合中のアリーナ改変とキット品の外界移動を遮断する入力アダプター。
 * itemDropCancelled が守る不変条件「開始時バックアップ以外のアイテムを残さない」
 * を、ドロップ以外の経路(コンテナ・額縁・取引・拾得)にも拡張する。
 */
class ArenaGuardListener(
    private val service: ArenaApplicationService
) : Listener {

    @EventHandler
    fun onBreak(event: BlockBreakEvent) {
        if (service.restrictionsOf(event.player)?.blockBreakCancelled == true) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onPlace(event: BlockPlaceEvent) {
        val restrictions = service.restrictionsOf(event.player) ?: return
        if (!restrictions.blockPlaceCancelled) return
        // 火打ち石は設置ではなく着火なので許可する(通常は BlockPlaceEvent を発火しないが、
        // 発火する実装でも着火の許可を維持する)
        if (event.itemInHand.type == Material.FLINT_AND_STEEL) return
        event.isCancelled = true
    }

    @EventHandler
    fun onDrop(event: PlayerDropItemEvent) {
        if (service.restrictionsOf(event.player)?.itemDropCancelled == true) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        if (foreignInventoryRestricted(event)) event.isCancelled = true
    }

    @EventHandler
    fun onInventoryDrag(event: InventoryDragEvent) {
        if (foreignInventoryRestricted(event)) event.isCancelled = true
    }

    /**
     * 自前の持ち物画面(CRAFTING/PLAYER)以外が開いている間の操作を全て遮断する。
     * 外来インベントリはインタラクト側でも塞ぐが、プラグイン等で開かれた場合の二番手防衛。
     */
    private fun foreignInventoryRestricted(event: InventoryInteractEvent): Boolean {
        val top = event.view.topInventory.type
        if (top == InventoryType.CRAFTING || top == InventoryType.PLAYER) return false
        val player = event.whoClicked as? Player ?: return false
        return service.restrictionsOf(player)?.inventoryTransferCancelled == true
    }

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        val restrictions = service.restrictionsOf(event.player) ?: return
        val block = event.clickedBlock
        if (restrictions.inventoryTransferCancelled && block != null && storesItems(block)) {
            denyInteract(event)
            return
        }
        if (restrictions.blockPlaceCancelled &&
            event.item?.type?.name?.endsWith("_SPAWN_EGG") == true
        ) {
            denyInteract(event)
        }
    }

    /**
     * 預け入れ可能なブロック。BlockState の InventoryHolder でコンテナ類を一括で拾い、
     * InventoryHolder を持たないエンダーチェスト・リスポーン地点を変更する
     * ベッド/リスポーンアンカー・植木鉢を明示する。
     */
    private fun storesItems(block: Block): Boolean {
        if (block.state is InventoryHolder) return true
        val type = block.type
        return type == Material.ENDER_CHEST || type == Material.RESPAWN_ANCHOR ||
            Tag.BEDS.isTagged(type) || type == Material.FLOWER_POT || type.name.startsWith("POTTED_")
    }

    /** 登録看板と同じく、ブロック操作とアイテム使用の両方を拒否する。 */
    private fun denyInteract(event: PlayerInteractEvent) {
        event.setUseInteractedBlock(Event.Result.DENY)
        event.setUseItemInHand(Event.Result.DENY)
    }

    @EventHandler
    fun onInteractEntity(event: PlayerInteractEntityEvent) {
        if (service.restrictionsOf(event.player)?.inventoryTransferCancelled != true) return
        val entity = event.rightClicked
        // InventoryHolder: チェスト付きトロッコ/ボート・村人(取引画面自体を開かせない)・Allay 等
        if (entity is InventoryHolder || entity is ItemFrame || entity is ArmorStand) {
            event.isCancelled = true
        }
    }

    // PlayerInteractEntityEvent のサブクラスだが HandlerList はイベントクラス毎に分かれる
    @EventHandler
    fun onInteractAtEntity(event: PlayerInteractAtEntityEvent) {
        onInteractEntity(event)
    }

    @EventHandler
    fun onArmorStandManipulate(event: PlayerArmorStandManipulateEvent) {
        if (service.restrictionsOf(event.player)?.inventoryTransferCancelled == true) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onEntityPlace(event: EntityPlaceEvent) {
        val player = event.player ?: return
        if (service.restrictionsOf(player)?.blockPlaceCancelled == true) event.isCancelled = true
    }

    @EventHandler
    fun onHangingPlace(event: HangingPlaceEvent) {
        val player = event.player ?: return
        if (service.restrictionsOf(player)?.blockPlaceCancelled == true) event.isCancelled = true
    }

    @EventHandler
    fun onBucketEmpty(event: PlayerBucketEmptyEvent) {
        if (service.restrictionsOf(event.player)?.blockPlaceCancelled == true) event.isCancelled = true
    }

    @EventHandler
    fun onBucketFill(event: PlayerBucketFillEvent) {
        if (service.restrictionsOf(event.player)?.blockBreakCancelled == true) event.isCancelled = true
    }

    @EventHandler
    fun onBucketEntity(event: PlayerBucketEntityEvent) {
        if (service.restrictionsOf(event.player)?.blockBreakCancelled == true) event.isCancelled = true
    }

    // PlayerBucketEntityEvent に置き換えられた非推奨イベント。発火する実装に備えて残す
    @Suppress("DEPRECATION")
    @EventHandler
    fun onBucketFish(event: PlayerBucketFishEvent) {
        if (service.restrictionsOf(event.player)?.blockBreakCancelled == true) event.isCancelled = true
    }

    @EventHandler
    fun onEntityPickupItem(event: EntityPickupItemEvent) {
        val player = event.entity as? Player ?: return
        if (service.restrictionsOf(player)?.itemPickupCancelled == true) event.isCancelled = true
    }

    @EventHandler
    fun onAttemptPickupItem(event: PlayerAttemptPickupItemEvent) {
        if (service.restrictionsOf(event.player)?.itemPickupCancelled == true) event.isCancelled = true
    }

    @EventHandler
    fun onPickupArrow(event: PlayerPickupArrowEvent) {
        // 観戦者が射込んだ矢/トライデントを参加者が回収する密輸経路も塞ぐ
        if (service.restrictionsOf(event.player)?.itemPickupCancelled == true) event.isCancelled = true
    }

    @EventHandler
    fun onHarvest(event: PlayerHarvestBlockEvent) {
        // ベリー系の収穫は拾得イベントを介さず直接インベントリへ入る
        if (service.restrictionsOf(event.player)?.itemPickupCancelled == true) event.isCancelled = true
    }

    @EventHandler
    fun onDispenseArmor(event: BlockDispenseArmorEvent) {
        val player = event.targetEntity as? Player ?: return
        if (service.restrictionsOf(player)?.itemPickupCancelled == true) event.isCancelled = true
    }

    @EventHandler
    fun onFertilize(event: BlockFertilizeEvent) {
        // 骨粉による樹木・作物の成長はブロック設置と同じアリーナ改変
        val player = event.player ?: return
        if (service.restrictionsOf(player)?.blockPlaceCancelled == true) event.isCancelled = true
    }

    @EventHandler
    fun onSignChange(event: SignChangeEvent) {
        // 未ワックス看板は誰でも文字を書き換えられるため、設置禁止と同じ制約で守る
        if (service.restrictionsOf(event.player)?.blockPlaceCancelled == true) event.isCancelled = true
    }

}

