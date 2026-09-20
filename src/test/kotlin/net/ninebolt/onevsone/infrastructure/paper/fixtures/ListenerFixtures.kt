package net.ninebolt.onevsone.infrastructure.paper.fixtures

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
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot

/** ArenaListener テスト用の実イベント構築と 2 人マッチ開始フィクスチャ。 */

internal fun TestEnv.deathEvent(player: Player): PlayerDeathEvent {
    val drops = mutableListOf(item(Material.STONE))
    return PlayerDeathEvent(player, mockk<DamageSource>(relaxed = true), drops, 0, Component.empty())
}

internal fun quitEvent(player: Player) =
    PlayerQuitEvent(player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED)

internal fun joinEvent(player: Player) = PlayerJoinEvent(player, Component.empty())

internal fun damageEvent(entity: Entity, damage: Double = 1.0) =
    EntityDamageEvent(entity, EntityDamageEvent.DamageCause.FALL, mockk<DamageSource>(relaxed = true), damage)

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
    action: Action = Action.RIGHT_CLICK_BLOCK
) = PlayerInteractEvent(player, action, null, block, BlockFace.SELF, hand)

internal fun breakEvent(player: Player, block: Block) = BlockBreakEvent(block, player)

/** 看板ではない実ブロック。 */
internal fun TestEnv.plainBlock(x: Int = 9, y: Int = 64, z: Int = 9): Block =
    world().getBlockAt(x, y, z).also { it.type = Material.STONE }

internal fun TestEnv.nonPlayer() =
    world().spawnEntity(Location(world(), 0.0, 64.0, 0.0), EntityType.PIG)
