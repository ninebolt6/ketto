package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.application.ArenaApplicationService
import net.ninebolt.onevsone.domain.DefeatCause
import net.ninebolt.onevsone.domain.ParticipantRestrictions
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
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
        if (service.matchOf(player.uniqueId.toKotlinUuid()) == null) return
        event.keepInventory = true
        event.drops.clear()
        if (!service.defeat(player.uniqueId.toKotlinUuid(), DefeatCause.DEATH)) {
            service.requestRespawn(player.uniqueId.toKotlinUuid())
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        val match = service.matchOf(player.uniqueId.toKotlinUuid()) ?: return
        if (ParticipantRestrictions.forState(match.state).damageCancelled) {
            event.isCancelled = true
        }
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
        if (match.resolvesVoidFall && event.to.y <= 0) {
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
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        val match = service.matchOf(event.player.uniqueId.toKotlinUuid()) ?: return
        if (ParticipantRestrictions.forState(match.state).commandsBlocked) {
            event.isCancelled = true
            messages.send(event.player, messages.commandBlocked)
        }
    }

}
