package net.ninebolt.onevsone

import org.bukkit.block.Sign
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.EquipmentSlot

class ArenaListener(
    private val service: ArenaService,
    private val messages: Messages
) : Listener {

    @EventHandler(priority = EventPriority.HIGH)
    fun onDeath(event: PlayerDeathEvent) {
        val player = event.entity
        if (service.arenaOf(player.uniqueId) == null) return
        event.keepInventory = true
        event.drops.clear()
        if (!service.lose(player, death = true)) {
            service.requestRespawn(player)
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        val arena = service.arenaOf(player.uniqueId) ?: return
        if (arena.state == ArenaState.ROUNDCOUNTDOWN) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        service.quit(event.player)
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        service.restorePending(event.player)
    }

    @EventHandler
    fun onMove(event: PlayerMoveEvent) {
        if (event is PlayerTeleportEvent) return
        val arena = service.arenaOf(event.player.uniqueId) ?: return
        if (arena.state == ArenaState.ROUNDCOUNTDOWN) {
            val from = event.from
            val to = event.to
            if (from.blockX != to.blockX || from.blockZ != to.blockZ) {
                event.setTo(from)
            }
        }
        if ((arena.state == ArenaState.INGAME || arena.state == ArenaState.ROUNDCOUNTDOWN) && arena.players.size == 2) {
            if (event.to.y <= 0) {
                service.lose(event.player, death = false)
            }
        }
    }

    @EventHandler
    fun onBreak(event: BlockBreakEvent) {
        val arena = service.arenaOf(event.player.uniqueId) ?: return
        if (arena.state == ArenaState.INGAME || arena.state == ArenaState.ROUNDCOUNTDOWN) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        if (event.hand != EquipmentSlot.HAND) return
        val block = event.clickedBlock ?: return
        if (block.state !is Sign) return
        val name = service.signArenaName(
            block.world.name,
            block.x.toDouble(),
            block.y.toDouble(),
            block.z.toDouble()
        ) ?: return
        val arena = service.arena(name) ?: return
        if (arena.state == ArenaState.WAITING || arena.state == ArenaState.ONEMORE) {
            service.join(event.player, arena)
        } else {
            messages.send(event.player, messages.arenaInGame)
        }
    }

    @EventHandler
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        val arena = service.arenaOf(event.player.uniqueId) ?: return
        if (arena.state != ArenaState.ONEMORE) {
            event.isCancelled = true
            messages.send(event.player, messages.commandBlocked)
        }
    }
}
