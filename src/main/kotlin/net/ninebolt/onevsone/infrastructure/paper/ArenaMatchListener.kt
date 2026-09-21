package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.application.ArenaApplicationService
import net.ninebolt.onevsone.domain.DamageAdmission
import net.ninebolt.onevsone.domain.DefeatCause
import net.ninebolt.onevsone.domain.ParticipantRestrictions
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

/**
 * 試合の進行に関わるイベントの入力アダプター。イベント/位置/引数の変換に限定し、
 * 状態別の制約判定は domain の ParticipantRestrictions に委譲する。
 */
class ArenaMatchListener(
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
        if (service.restrictionsOf(player)?.damageCancelled == true) {
            event.isCancelled = true
        }
    }

    /**
     * エンティティ起因ダメージは責任者(causingEntity: 投射物の射手や設置者まで辿れる)を
     * プレイヤーへ解決し、受理判定は domain の DamageAdmission に委譲する。
     * victim が非プレイヤーでも加害者側を検査するため早期 return はしない。
     */
    private fun onEntityDamage(event: EntityDamageByEntityEvent) {
        val victim = event.entity as? Player
        val attacker = event.damageSource.causingEntity as? Player
        val allowed = DamageAdmission.allows(
            victimId = victim?.uniqueId?.toKotlinUuid(),
            attackerId = attacker?.uniqueId?.toKotlinUuid(),
            victimMatch = victim?.let { service.matchOf(it.uniqueId.toKotlinUuid()) },
            attackerMatch = attacker?.let { service.matchOf(it.uniqueId.toKotlinUuid()) }
        )
        if (!allowed) event.isCancelled = true
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
        // PlayerTeleportEvent は別 HandlerList を持つためここには届かない
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
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        if (service.restrictionsOf(event.player)?.commandsBlocked == true) {
            event.isCancelled = true
            messages.send(event.player, messages.commandBlocked)
        }
    }
}
