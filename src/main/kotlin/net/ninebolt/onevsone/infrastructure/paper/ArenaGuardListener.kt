package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.application.ArenaApplicationService
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.block.Block
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Player
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
        // Flint and steel normally fires no BlockPlaceEvent, but some implementations do
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
            event.denyUse()
            return
        }
        if (restrictions.blockPlaceCancelled &&
            event.item?.type?.name?.endsWith("_SPAWN_EGG") == true
        ) {
            event.denyUse()
        }
    }

    // Ender chests, beds, respawn anchors and flower pots hold items but have no InventoryHolder block state
    private fun storesItems(block: Block): Boolean {
        if (block.state is InventoryHolder) return true
        val type = block.type
        return type == Material.ENDER_CHEST || type == Material.RESPAWN_ANCHOR ||
            Tag.BEDS.isTagged(type) || type == Material.FLOWER_POT || type.name.startsWith("POTTED_")
    }

    @EventHandler
    fun onInteractEntity(event: PlayerInteractEntityEvent) {
        if (service.restrictionsOf(event.player)?.inventoryTransferCancelled != true) return
        val entity = event.rightClicked
        // Villagers are InventoryHolders too, so cancelling also suppresses the trading UI
        if (entity is InventoryHolder || entity is ItemFrame || entity is ArmorStand) {
            event.isCancelled = true
        }
    }

    // A subclass of PlayerInteractEntityEvent, but HandlerLists are split per event class
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

    // Deprecated event superseded by PlayerBucketEntityEvent. Kept for implementations that still fire it
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
        if (service.restrictionsOf(event.player)?.itemPickupCancelled == true) event.isCancelled = true
    }

    @EventHandler
    fun onHarvest(event: PlayerHarvestBlockEvent) {
        // Berry-type harvests go straight into the inventory without a pickup event
        if (service.restrictionsOf(event.player)?.itemPickupCancelled == true) event.isCancelled = true
    }

    @EventHandler
    fun onDispenseArmor(event: BlockDispenseArmorEvent) {
        val player = event.targetEntity as? Player ?: return
        if (service.restrictionsOf(player)?.itemPickupCancelled == true) event.isCancelled = true
    }

    @EventHandler
    fun onFertilize(event: BlockFertilizeEvent) {
        // Bone meal grows trees and crops, modifying arena blocks
        val player = event.player ?: return
        if (service.restrictionsOf(player)?.blockPlaceCancelled == true) event.isCancelled = true
    }

    @EventHandler
    fun onSignChange(event: SignChangeEvent) {
        // Anyone can rewrite an unwaxed sign
        if (service.restrictionsOf(event.player)?.blockPlaceCancelled == true) event.isCancelled = true
    }

}

