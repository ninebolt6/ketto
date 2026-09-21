package net.ninebolt.onevsone.infrastructure.paper.fixtures

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.entity.Zombie
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.block.BlockFace
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.entity.Item
import org.bukkit.event.Event
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Assertions.assertTrue
import org.mockbukkit.mockbukkit.entity.LivingEntityMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.mockbukkit.mockbukkit.simulate.entity.PlayerSimulation

/** アリーナ系リスナーテスト用の実イベント構築と 2 人マッチ開始フィクスチャ。 */

/** 環境ダメージ(落下相当)。causingEntity なしの実 DamageSource。 */
internal fun genericDamage(): DamageSource = DamageSource.builder(DamageType.GENERIC).build()

/**
 * 実 DamageSource の攻撃。directEntity 非 null のため simulateDamage は
 * EntityDamageByEntityEvent を発火し、causingEntity で帰属判定される。
 */
internal fun attackDamage(attacker: Entity, type: DamageType = DamageType.PLAYER_ATTACK): DamageSource =
    DamageSource.builder(type).withDirectEntity(attacker).withCausingEntity(attacker).build()

/** direct(矢等)と causing(射手)が異なる投射物の実 DamageSource。 */
internal fun projectileDamage(direct: Entity, causing: Entity): DamageSource =
    DamageSource.builder(DamageType.GENERIC).withDirectEntity(direct).withCausingEntity(causing).build()

/** PlayerMock.simulate* は委譲シムで @Deprecated のため、非推奨の PlayerSimulation を直接使う。 */
internal fun PlayerMock.simulation() = PlayerSimulation(this)

/** MockBukkit 4.103 では assertEventFired 系が deprecated のため、発火済みイベントを直接検査する。 */
internal inline fun <reified T : Event> TestEnv.assertFired(noinline predicate: (T) -> Boolean = { true }) {
    val fired = server.pluginManager.firedEvents.toList().filterIsInstance<T>()
    assertTrue(fired.any(predicate), "no fired ${T::class.simpleName} matched; fired=$fired")
}

/** 前提不成立(空気ブロック等)で null になる戻り値をテスト向けに非 null 化する。 */
internal fun PlayerSimulation.breakBlock(block: Block): BlockBreakEvent =
    simulateBlockBreak(block) ?: error("simulateBlockBreak returned null")

internal fun PlayerSimulation.placeBlock(material: Material, location: Location): BlockPlaceEvent =
    simulateBlockPlace(material, location) ?: error("simulateBlockPlace returned null")

internal fun damageEvent(entity: Entity, damage: Double = 1.0) =
    EntityDamageEvent(entity, EntityDamageEvent.DamageCause.FALL, genericDamage(), damage)

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

/** simulateDamage を持つ LivingEntityMock 系のモブ。 */
internal fun TestEnv.mob(): LivingEntityMock =
    world().spawn(Location(world(), 0.0, 64.0, 0.0), Zombie::class.java) as LivingEntityMock

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
