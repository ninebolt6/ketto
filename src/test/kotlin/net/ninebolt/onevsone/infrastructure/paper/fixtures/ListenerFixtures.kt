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
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.entity.Item
import org.bukkit.event.Event
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import kotlin.test.assertTrue
import org.mockbukkit.mockbukkit.entity.LivingEntityMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.mockbukkit.mockbukkit.simulate.entity.PlayerSimulation

/** Real-event construction and two-player match start fixtures for arena listener tests. */

/** Environmental damage (fall equivalent). A real DamageSource with no causingEntity. */
internal fun genericDamage(): DamageSource = DamageSource.builder(DamageType.GENERIC).build()

/**
 * An attack via a real DamageSource. With directEntity non-null, simulateDamage
 * fires EntityDamageByEntityEvent and attribution is judged by causingEntity.
 */
internal fun attackDamage(attacker: Entity, type: DamageType = DamageType.PLAYER_ATTACK): DamageSource =
    DamageSource.builder(type).withDirectEntity(attacker).withCausingEntity(attacker).build()

/** Real DamageSource for projectiles where direct (arrow) and causing (shooter) differ. */
internal fun projectileDamage(direct: Entity, causing: Entity): DamageSource =
    DamageSource.builder(DamageType.GENERIC).withDirectEntity(direct).withCausingEntity(causing).build()

/** PlayerMock.simulate* is a delegating shim marked @Deprecated, so use the supported PlayerSimulation directly. */
internal fun PlayerMock.simulation() = PlayerSimulation(this)

/** MockBukkit 4.103 deprecates the assertEventFired family, so inspect fired events directly. */
internal inline fun <reified T : Event> TestEnv.assertFired(noinline predicate: (T) -> Boolean = { true }) {
    val fired = server.pluginManager.firedEvents.toList().filterIsInstance<T>()
    assertTrue(fired.any(predicate), "no fired ${T::class.simpleName} matched; fired=$fired")
}

/** Turns the null-on-unmet-precondition return (air blocks etc.) into a non-null one for tests. */
internal fun PlayerSimulation.breakBlock(block: Block): BlockBreakEvent =
    simulateBlockBreak(block) ?: error("simulateBlockBreak returned null")

internal fun PlayerSimulation.placeBlock(material: Material, location: Location): BlockPlaceEvent =
    simulateBlockPlace(material, location) ?: error("simulateBlockPlace returned null")

internal fun TestEnv.twoPlayerIngame(): Pair<ArenaPlayerMock, ArenaPlayerMock> {
    val arena = newArena()
    val p1 = player("Alice")
    val p2 = player("Bob")
    join(p1, arena)
    join(p2, arena)
    tick(6)
    return p1 to p2
}

/** Turns a real block into a sign and returns it. Later getBlockAt calls return the same state. */
internal fun TestEnv.signBlock(x: Int, y: Int, z: Int): Block =
    world().getBlockAt(x, y, z).also { it.type = Material.OAK_SIGN }

internal fun interact(
    player: Player,
    block: Block,
    hand: EquipmentSlot = EquipmentSlot.HAND,
    action: Action = Action.RIGHT_CLICK_BLOCK,
    item: ItemStack? = null
) = PlayerInteractEvent(player, action, item, block, BlockFace.SELF, hand)

/** Drops a real item entity to build a drop event. */
internal fun TestEnv.dropEvent(player: Player): PlayerDropItemEvent =
    PlayerDropItemEvent(player, world().dropItem(player.location, item(Material.STONE)))

/** A real block of the given type. Reproduces BlockState checks like containers on MockBukkit. */
internal fun TestEnv.blockOf(type: Material, x: Int = 8, y: Int = 64, z: Int = 8): Block =
    world().getBlockAt(x, y, z).also { it.type = type }

internal fun TestEnv.itemEntity(): Item =
    world().dropItem(Location(world(), 0.0, 64.0, 0.0), item(Material.STONE))

internal fun TestEnv.spawn(type: EntityType) =
    world().spawnEntity(Location(world(), 0.0, 64.0, 0.0), type)

/** A LivingEntityMock mob, which supports simulateDamage. */
internal fun TestEnv.mob(): LivingEntityMock =
    world().spawn(Location(world(), 0.0, 64.0, 0.0), Zombie::class.java) as LivingEntityMock

internal fun TestEnv.plainBlock(x: Int = 9, y: Int = 64, z: Int = 9): Block =
    world().getBlockAt(x, y, z).also { it.type = Material.STONE }

internal fun TestEnv.nonPlayer() =
    world().spawnEntity(Location(world(), 0.0, 64.0, 0.0), EntityType.PIG)
