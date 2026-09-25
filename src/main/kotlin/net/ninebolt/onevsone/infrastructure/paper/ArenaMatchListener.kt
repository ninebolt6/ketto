package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.application.ArenaApplicationService
import net.ninebolt.onevsone.domain.DamageAdmission
import net.ninebolt.onevsone.domain.DefeatCause
import net.ninebolt.onevsone.domain.ParticipantRestrictions
import net.ninebolt.onevsone.infrastructure.paper.message.Message
import net.ninebolt.onevsone.infrastructure.paper.message.Messenger
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import kotlin.uuid.toKotlinUuid

class ArenaMatchListener(
    private val service: ArenaApplicationService,
    private val lookup: PaperPlayerLookup,
    private val messenger: Messenger,
) : Listener {

    @EventHandler(priority = EventPriority.HIGH)
    fun onDeath(event: PlayerDeathEvent) {
        val player = event.entity
        val id = player.uniqueId.toKotlinUuid()
        if (service.matchOf(id) == null) return
        event.keepInventory = true
        event.drops.clear()
        // keepInventory protects only items, not experience
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
        val player = event.entity as? Player ?: return
        if (service.restrictionsOf(player)?.damageCancelled == true) {
            event.isCancelled = true
        }
    }

    private fun onEntityDamage(event: EntityDamageByEntityEvent) {
        val victim = event.entity as? Player
        val attacker = event.damageSource.causingEntity as? Player
        val allowed = DamageAdmission.allows(
            victimId = victim?.let { it.uniqueId.toKotlinUuid() },
            attackerId = attacker?.let { it.uniqueId.toKotlinUuid() },
            victimMatch = victim?.let { service.matchOf(it.uniqueId.toKotlinUuid()) },
            attackerMatch = attacker?.let { service.matchOf(it.uniqueId.toKotlinUuid()) },
        )
        if (!allowed) event.isCancelled = true
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        // A quitting player can no longer be fetched from Server, so the event's Player must be resolved synchronously
        lookup.scopeQuitting(event.player) {
            service.quit(event.player.uniqueId.toKotlinUuid())
        }
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        service.restorePending(event.player.uniqueId.toKotlinUuid())
    }

    @EventHandler
    fun onMove(event: PlayerMoveEvent) {
        // PlayerTeleportEvent has its own HandlerList and never reaches this handler
        val match = service.matchOf(event.player.uniqueId.toKotlinUuid()) ?: return
        if (ParticipantRestrictions.forState(match.state).horizontalMoveFrozen) {
            val from = event.from
            val to = event.to
            if (from.blockX != to.blockX || from.blockZ != to.blockZ) {
                event.setTo(from)
            }
        }
        // Since 1.18 a world's min height can be below y=0
        if (match.resolvesVoidFall && event.to.y <= (event.to.world?.minHeight ?: 0)) {
            service.defeat(event.player.uniqueId.toKotlinUuid(), DefeatCause.FALL)
        }
    }

    @EventHandler
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        if (service.restrictionsOf(event.player)?.commandsBlocked == true) {
            event.isCancelled = true
            messenger.send(event.player, Message.CommandBlocked)
        }
    }
}
