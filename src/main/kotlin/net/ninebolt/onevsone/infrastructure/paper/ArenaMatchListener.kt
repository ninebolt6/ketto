package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.application.MatchParticipationService
import net.ninebolt.onevsone.domain.DamageAdmission
import net.ninebolt.onevsone.domain.DamagePolicy
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
    private val participation: MatchParticipationService,
    private val lookup: PaperPlayerLookup,
    private val messenger: Messenger,
) : Listener {

    @EventHandler(priority = EventPriority.HIGH)
    fun onDeath(event: PlayerDeathEvent) {
        val player = event.entity
        val id = player.uniqueId.toKotlinUuid()
        if (participation.matchOf(id) == null) return
        event.keepInventory = true
        event.drops.clear()
        // keepInventory protects only items, not experience
        event.droppedExp = 0
        event.keepLevel = true
        if (!participation.defeat(id, DefeatCause.DEATH)) {
            participation.requestRespawn(id)
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onDamage(event: EntityDamageEvent) {
        if (event is EntityDamageByEntityEvent) {
            onEntityDamage(event)
            return
        }
        val player = event.entity as? Player ?: return
        if (participation.restrictionsOf(player)?.damagePolicy == DamagePolicy.BLOCKED) {
            event.isCancelled = true
        }
    }

    private fun onEntityDamage(event: EntityDamageByEntityEvent) {
        val allowed = DamageAdmission.allows(
            victim = sideOf(event.entity as? Player),
            attacker = sideOf(event.damageSource.causingEntity as? Player),
        )
        if (!allowed) event.isCancelled = true
    }

    private fun sideOf(player: Player?): DamageAdmission.Side? {
        val id = player?.uniqueId?.toKotlinUuid() ?: return null
        return participation.matchOf(id)?.let { DamageAdmission.Side(id, it) }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        // A quitting player can no longer be fetched from Server, so the event's Player must be resolved synchronously
        lookup.scopeQuitting(event.player) {
            participation.quit(event.player.uniqueId.toKotlinUuid())
        }
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        participation.restorePending(event.player.uniqueId.toKotlinUuid())
    }

    @EventHandler
    fun onMove(event: PlayerMoveEvent) {
        // PlayerTeleportEvent has its own HandlerList and never reaches this handler
        val match = participation.matchOf(event.player.uniqueId.toKotlinUuid()) ?: return
        if (ParticipantRestrictions.forState(match.state.kind).horizontalMoveFrozen) {
            val from = event.from
            val to = event.to
            if (from.blockX != to.blockX || from.blockZ != to.blockZ) {
                event.setTo(from)
            }
        }
        // Since 1.18 a world's min height can be below y=0
        if (match.resolvesVoidFall && event.to.y <= (event.to.world?.minHeight ?: 0)) {
            participation.defeat(event.player.uniqueId.toKotlinUuid(), DefeatCause.FALL)
        }
    }

    @EventHandler
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        if (participation.restrictionsOf(event.player)?.commandsBlocked == true) {
            event.isCancelled = true
            messenger.send(event.player, Message.CommandBlocked)
        }
    }
}
