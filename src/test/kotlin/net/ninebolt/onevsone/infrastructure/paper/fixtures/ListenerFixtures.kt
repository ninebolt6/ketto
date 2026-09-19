package net.ninebolt.onevsone.infrastructure.paper.fixtures

import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.Sign
import org.bukkit.block.sign.Side
import org.bukkit.block.sign.SignSide
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.inventory.EquipmentSlot

/** ArenaListener テスト用のイベントモック生成と 2 人マッチ開始フィクスチャ。 */

internal fun TestEnv.deathEvent(player: Player): PlayerDeathEvent {
    val event = mockk<PlayerDeathEvent>(relaxed = true)
    val drops = mutableListOf(item(Material.STONE))
    every { event.entity } returns player
    every { event.drops } returns drops
    return event
}

internal fun moveEvent(player: Player, from: Location, to: Location): PlayerMoveEvent {
    val event = mockk<PlayerMoveEvent>(relaxed = true)
    every { event.player } returns player
    every { event.from } returns from
    every { event.to } returns to
    return event
}

internal fun TestEnv.twoPlayerIngame(): Pair<Player, Player> {
    val arena = newArena()
    val p1 = player("Alice")
    val p2 = player("Bob")
    join(p1, arena)
    join(p2, arena)
    tick(6)
    return p1 to p2
}

internal fun TestEnv.signBlock(x: Int, y: Int, z: Int): Block {
    val block = mockk<Block>(relaxed = true)
    val sign = mockk<Sign>(relaxed = true)
    val side = mockk<SignSide>(relaxed = true)
    val w = world()
    every { sign.getSide(Side.FRONT) } returns side
    every { block.state } returns sign
    every { block.world } returns w
    every { block.x } returns x
    every { block.y } returns y
    every { block.z } returns z
    every { w.getBlockAt(x, y, z) } returns block
    return block
}

internal fun interact(
    player: Player,
    block: Block,
    hand: EquipmentSlot = EquipmentSlot.HAND
): PlayerInteractEvent {
    val event = mockk<PlayerInteractEvent>(relaxed = true)
    every { event.player } returns player
    every { event.action } returns Action.RIGHT_CLICK_BLOCK
    every { event.hand } returns hand
    every { event.clickedBlock } returns block
    return event
}
