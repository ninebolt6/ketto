package net.ninebolt.onevsone.infrastructure.paper.fixtures

import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.damage.DamageSource
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.block.BlockFace
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.entity.Item
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack

/** ArenaListener テスト用の実イベント構築と 2 人マッチ開始フィクスチャ。 */

internal fun TestEnv.deathEvent(player: Player, droppedExp: Int = 0): PlayerDeathEvent {
    val drops = mutableListOf(item(Material.STONE))
    return PlayerDeathEvent(player, mockk<DamageSource>(relaxed = true), drops, droppedExp, Component.empty())
}

internal fun quitEvent(player: Player) =
    PlayerQuitEvent(player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED)

internal fun joinEvent(player: Player) = PlayerJoinEvent(player, Component.empty())

internal fun damageEvent(entity: Entity, damage: Double = 1.0) =
    EntityDamageEvent(entity, EntityDamageEvent.DamageCause.FALL, mockk<DamageSource>(relaxed = true), damage)

/**
 * エンティティ起因ダメージ。causingEntity は DamageSource からしか取れないため
 * mockk で帰属者を注入する(MockBukkit 未実装 API の限定用途)。
 * EntityDamageByEntityEvent は Paper 1.21 で全コンストラクタが非推奨だが、
 * テストでのイベント生成には代替が無いため抑制する。
 */
@Suppress("DEPRECATION")
internal fun entityDamageEvent(
    damager: Entity,
    victim: Entity,
    causingEntity: Entity? = damager,
    damage: Double = 1.0
): EntityDamageByEntityEvent {
    val source = mockk<DamageSource>()
    every { source.causingEntity } returns causingEntity
    every { source.directEntity } returns damager
    return EntityDamageByEntityEvent(
        damager, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, source, damage
    )
}

internal fun moveEvent(player: Player, from: Location, to: Location) = PlayerMoveEvent(player, from, to)

internal fun TestEnv.twoPlayerIngame(): Pair<ArenaPlayerMock, ArenaPlayerMock> {
    val arena = newArena()
    val p1 = player("Alice")
    val p2 = player("Bob")
    join(p1, arena)
    join(p2, arena)
    tick(6)
    return p1 to p2
}

/** 実ブロックを看板に変えて返す。以後の getBlockAt は同じ状態を返す。 */
internal fun TestEnv.signBlock(x: Int, y: Int, z: Int): Block =
    world().getBlockAt(x, y, z).also { it.type = Material.OAK_SIGN }

internal fun interact(
    player: Player,
    block: Block,
    hand: EquipmentSlot = EquipmentSlot.HAND,
    action: Action = Action.RIGHT_CLICK_BLOCK,
    item: ItemStack? = null
) = PlayerInteractEvent(player, action, item, block, BlockFace.SELF, hand)

internal fun breakEvent(player: Player, block: Block) = BlockBreakEvent(block, player)

/** 実アイテムエンティティを落としてドロップイベントを作る。 */
internal fun TestEnv.dropEvent(player: Player): PlayerDropItemEvent =
    PlayerDropItemEvent(player, world().dropItem(player.location, item(Material.STONE)))

/** 指定 type の実ブロック。コンテナ等の BlockState 判定を MockBukkit 上で再現する。 */
internal fun TestEnv.blockOf(type: Material, x: Int = 8, y: Int = 64, z: Int = 8): Block =
    world().getBlockAt(x, y, z).also { it.type = type }

internal fun TestEnv.itemEntity(): Item =
    world().dropItem(Location(world(), 0.0, 64.0, 0.0), item(Material.STONE))

internal fun TestEnv.spawn(type: EntityType) =
    world().spawnEntity(Location(world(), 0.0, 64.0, 0.0), type)

/** 設置イベント。held 省略時は石ブロックを持つ想定。 */
internal fun TestEnv.placeEvent(player: Player, held: ItemStack? = null): BlockPlaceEvent {
    val block = plainBlock()
    return BlockPlaceEvent(block, block.state, block, held ?: item(Material.STONE), player, true, EquipmentSlot.HAND)
}

/** 看板ではない実ブロック。 */
internal fun TestEnv.plainBlock(x: Int = 9, y: Int = 64, z: Int = 9): Block =
    world().getBlockAt(x, y, z).also { it.type = Material.STONE }

internal fun TestEnv.nonPlayer() =
    world().spawnEntity(Location(world(), 0.0, 64.0, 0.0), EntityType.PIG)
