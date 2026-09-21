package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.application.ArenaApplicationService
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.DefeatCause
import net.ninebolt.onevsone.domain.ParticipantRestrictions
import net.ninebolt.onevsone.domain.TeleportTrigger
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.block.Block
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDispenseArmorEvent
import org.bukkit.event.block.BlockFertilizeEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.SignChangeEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.EntityPlaceEvent
import org.bukkit.event.entity.PlayerDeathEvent
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
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerHarvestBlockEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerPickupArrowEvent
import org.bukkit.event.player.PlayerPortalEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.vehicle.VehicleEnterEvent
import org.bukkit.inventory.InventoryHolder
import kotlin.uuid.toKotlinUuid

/**
 * Bukkit イベントの入力アダプター。イベント/位置/引数の変換に限定し、
 * 状態別の制約判定は domain の ParticipantRestrictions に委譲する。
 * 参加看板のイベントは ArenaSignListener が担う。
 */
class ArenaListener(
    private val service: ArenaApplicationService,
    private val lookup: PaperPlayerLookup,
    private val messages: Messages
) : Listener {

    @EventHandler(priority = EventPriority.HIGH)
    fun onDeath(event: PlayerDeathEvent) {
        val player = event.entity
        val id = player.uniqueId.toKotlinUuid()
        if (service.matchOf(id) == null) return
        event.keepInventory = true
        event.drops.clear()
        // keepInventory はアイテムのみを守るため、経験値もドロップさせず保持する
        event.droppedExp = 0
        event.keepLevel = true
        if (!service.defeat(id, DefeatCause.DEATH)) {
            service.requestRespawn(id)
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onDamage(event: EntityDamageEvent) {
        if (event is EntityDamageByEntityEvent) {
            onEntityDamage(event)
            return
        }
        // 落下・火・溶岩などの環境ダメージは帰属できないため従来通り敗北として受理する
        val player = event.entity as? Player ?: return
        val match = service.matchOf(player.uniqueId.toKotlinUuid()) ?: return
        if (ParticipantRestrictions.forState(match.state).damageCancelled) {
            event.isCancelled = true
        }
    }

    /**
     * エンティティ起因ダメージは責任者(causingEntity: 投射物の射手や設置者まで辿れる)を
     * 解決し、制限状態の参加者が関わる場合は「INGAME で同一マッチの対戦相手または本人」
     * 由来のみ許可する。MOB・第三者・別マッチからの干渉と、参加者→部外者/MOB への攻撃を
     * 全て塞ぐ。victim が非プレイヤーでも加害者側を検査するため早期 return はしない。
     */
    private fun onEntityDamage(event: EntityDamageByEntityEvent) {
        val victim = event.entity as? Player
        val attacker = event.damageSource.causingEntity as? Player
        val victimMatch = victim?.let { service.matchOf(it.uniqueId.toKotlinUuid()) }
        val attackerMatch = attacker?.let { service.matchOf(it.uniqueId.toKotlinUuid()) }

        fun limitsDamage(match: ArenaMatch?): Boolean = match?.let {
            ParticipantRestrictions.forState(it.state).let { r ->
                r.damageCancelled || r.opponentDamageOnly
            }
        } == true

        if (!limitsDamage(victimMatch) && !limitsDamage(attackerMatch)) return

        val opponentOrSelf = victim != null && attacker != null && victimMatch != null &&
            ParticipantRestrictions.forState(victimMatch.state).opponentDamageOnly &&
            (attacker === victim || victimMatch.participants.any {
                it.id == attacker.uniqueId.toKotlinUuid() && it.id != victim.uniqueId.toKotlinUuid()
            })
        if (!opponentOrSelf) event.isCancelled = true
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        // 切断中プレイヤーは Server から取得できなくなるため、
        // イベントの Player を同期処理中だけ解決できるスコープで呼ぶ。
        lookup.scopeQuitting(event.player) {
            service.quit(event.player.uniqueId.toKotlinUuid(), event.player.name)
        }
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        service.restorePending(event.player.uniqueId.toKotlinUuid(), event.player.name)
    }

    @EventHandler
    fun onMove(event: PlayerMoveEvent) {
        if (event is PlayerTeleportEvent) return
        val match = service.matchOf(event.player.uniqueId.toKotlinUuid()) ?: return
        if (ParticipantRestrictions.forState(match.state).horizontalMoveFrozen) {
            val from = event.from
            val to = event.to
            if (from.blockX != to.blockX || from.blockZ != to.blockZ) {
                event.setTo(from)
            }
        }
        // 1.18+ の世界は負の高さを持つため、奈落判定は移動先ワールドの最低高度を使う
        if (match.resolvesVoidFall && event.to.y <= (event.to.world?.minHeight ?: 0)) {
            service.defeat(event.player.uniqueId.toKotlinUuid(), DefeatCause.FALL)
        }
    }

    @EventHandler
    fun onBreak(event: BlockBreakEvent) {
        val match = service.matchOf(event.player.uniqueId.toKotlinUuid()) ?: return
        if (ParticipantRestrictions.forState(match.state).blockBreakCancelled) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onPlace(event: BlockPlaceEvent) {
        val match = service.matchOf(event.player.uniqueId.toKotlinUuid()) ?: return
        if (!ParticipantRestrictions.forState(match.state).blockPlaceCancelled) return
        // 火打ち石は設置ではなく着火なので許可する(通常は BlockPlaceEvent を発火しないが、
        // 発火する実装でも着火の許可を維持する)
        if (event.itemInHand.type == Material.FLINT_AND_STEEL) return
        event.isCancelled = true
    }

    @EventHandler
    fun onDrop(event: PlayerDropItemEvent) {
        val match = service.matchOf(event.player.uniqueId.toKotlinUuid()) ?: return
        if (ParticipantRestrictions.forState(match.state).itemDropCancelled) {
            event.isCancelled = true
        }
    }

    // ---- キット品の外界移動と直接獲得の遮断 -------------------------------
    // itemDropCancelled が守る不変条件「開始時バックアップ以外のアイテムを残さない」
    // を、ドロップ以外の経路(コンテナ・額縁・取引・拾得)にも拡張する。

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
        return restrictionsOf(player)?.inventoryTransferCancelled == true
    }

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        val restrictions = restrictionsOf(event.player) ?: return
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
        if (restrictionsOf(event.player)?.inventoryTransferCancelled != true) return
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
        if (restrictionsOf(event.player)?.inventoryTransferCancelled == true) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onEntityPlace(event: EntityPlaceEvent) {
        val player = event.player ?: return
        if (restrictionsOf(player)?.blockPlaceCancelled == true) event.isCancelled = true
    }

    @EventHandler
    fun onHangingPlace(event: HangingPlaceEvent) {
        val player = event.player ?: return
        if (restrictionsOf(player)?.blockPlaceCancelled == true) event.isCancelled = true
    }

    @EventHandler
    fun onBucketEmpty(event: PlayerBucketEmptyEvent) {
        if (restrictionsOf(event.player)?.blockPlaceCancelled == true) event.isCancelled = true
    }

    @EventHandler
    fun onBucketFill(event: PlayerBucketFillEvent) {
        if (restrictionsOf(event.player)?.blockBreakCancelled == true) event.isCancelled = true
    }

    @EventHandler
    fun onBucketEntity(event: PlayerBucketEntityEvent) {
        if (restrictionsOf(event.player)?.blockBreakCancelled == true) event.isCancelled = true
    }

    // PlayerBucketEntityEvent に置き換えられた非推奨イベント。発火する実装に備えて残す
    @Suppress("DEPRECATION")
    @EventHandler
    fun onBucketFish(event: PlayerBucketFishEvent) {
        if (restrictionsOf(event.player)?.blockBreakCancelled == true) event.isCancelled = true
    }

    @EventHandler
    fun onEntityPickupItem(event: EntityPickupItemEvent) {
        val player = event.entity as? Player ?: return
        if (restrictionsOf(player)?.itemPickupCancelled == true) event.isCancelled = true
    }

    @EventHandler
    fun onAttemptPickupItem(event: PlayerAttemptPickupItemEvent) {
        if (restrictionsOf(event.player)?.itemPickupCancelled == true) event.isCancelled = true
    }

    @EventHandler
    fun onPickupArrow(event: PlayerPickupArrowEvent) {
        // 観戦者が射込んだ矢/トライデントを参加者が回収する密輸経路も塞ぐ
        if (restrictionsOf(event.player)?.itemPickupCancelled == true) event.isCancelled = true
    }

    @EventHandler
    fun onHarvest(event: PlayerHarvestBlockEvent) {
        // ベリー系の収穫は拾得イベントを介さず直接インベントリへ入る
        if (restrictionsOf(event.player)?.itemPickupCancelled == true) event.isCancelled = true
    }

    @EventHandler
    fun onDispenseArmor(event: BlockDispenseArmorEvent) {
        val player = event.targetEntity as? Player ?: return
        if (restrictionsOf(player)?.itemPickupCancelled == true) event.isCancelled = true
    }

    @EventHandler
    fun onFertilize(event: BlockFertilizeEvent) {
        // 骨粉による樹木・作物の成長はブロック設置と同じアリーナ改変
        val player = event.player ?: return
        if (restrictionsOf(player)?.blockPlaceCancelled == true) event.isCancelled = true
    }

    @EventHandler
    fun onSignChange(event: SignChangeEvent) {
        // 未ワックス看板は誰でも文字を書き換えられるため、設置禁止と同じ制約で守る
        if (restrictionsOf(event.player)?.blockPlaceCancelled == true) event.isCancelled = true
    }

    private fun restrictionsOf(player: Player): ParticipantRestrictions? =
        service.matchOf(player.uniqueId.toKotlinUuid())
            ?.let { ParticipantRestrictions.forState(it.state) }

    // ---- テレポート逃走の遮断 ------------------------------------------------

    @EventHandler
    fun onTeleport(event: PlayerTeleportEvent) {
        restrictTeleport(event)
    }

    // PlayerPortalEvent は独自 HandlerList を持ち PlayerTeleportEvent には届かない
    @EventHandler
    fun onPortal(event: PlayerPortalEvent) {
        restrictTeleport(event)
    }

    private fun restrictTeleport(event: PlayerTeleportEvent) {
        val restrictions = restrictionsOf(event.player) ?: return
        // プラグイン自身の移送は cause が PLUGIN とは限らないため、マーカーで先に識別する
        val trigger = when {
            lookup.isPluginTeleport(event.player.uniqueId.toKotlinUuid()) -> TeleportTrigger.INTERNAL
            event.cause == PlayerTeleportEvent.TeleportCause.ENDER_PEARL -> TeleportTrigger.ENDER_PEARL
            else -> TeleportTrigger.EXTERNAL
        }
        if (!restrictions.teleportRestriction.allows(trigger)) event.isCancelled = true
    }

    /** 移動凍結中の乗車は水平移動をバイパスするため遮断する。 */
    @EventHandler
    fun onVehicleEnter(event: VehicleEnterEvent) {
        val player = event.entered as? Player ?: return
        if (restrictionsOf(player)?.horizontalMoveFrozen == true) event.isCancelled = true
    }

    @EventHandler
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        val match = service.matchOf(event.player.uniqueId.toKotlinUuid()) ?: return
        if (ParticipantRestrictions.forState(match.state).commandsBlocked) {
            event.isCancelled = true
            messages.send(event.player, messages.commandBlocked)
        }
    }

}
