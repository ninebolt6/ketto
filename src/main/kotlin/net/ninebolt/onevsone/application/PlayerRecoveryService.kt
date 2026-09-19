package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.port.BackupRef
import net.ninebolt.onevsone.application.port.FailureReporter
import net.ninebolt.onevsone.application.port.InventoryBackupPort
import net.ninebolt.onevsone.application.port.LobbyRepository
import net.ninebolt.onevsone.application.port.MatchPresentationPort
import net.ninebolt.onevsone.application.port.PersistenceFailure
import net.ninebolt.onevsone.application.port.PlayerHandle
import net.ninebolt.onevsone.application.port.PlayerPort
import java.util.UUID

/**
 * 未復元バックアップと復元トークン(RestoreTicket)を管理する。
 * 参加中かどうかと独立して、終了直後の死亡→切断・停止・次回ログインを扱う。
 *
 * 遅延コールバックは「ticket の同一性」で有効性を判断し、
 * 試合の世代トークン(MatchToken)とは分離する。
 */
class PlayerRecoveryService(
    private val backups: InventoryBackupPort,
    private val players: PlayerPort,
    private val lobby: LobbyRepository,
    private val presentation: MatchPresentationPort,
    private val failures: FailureReporter
) {
    /** 復元対象 1 件のトークン。遅延コールバックは参照同一性で照合する。 */
    class RestoreTicket(val ref: BackupRef)

    private val ticketsByUuid = mutableMapOf<UUID, RestoreTicket>()
    private val ticketsByName = mutableMapOf<String, RestoreTicket>()

    /** 起動時に呼ばれる。 */
    fun loadPersisted() {
        backups.pendingBackups().forEach { ref ->
            val ticket = RestoreTicket(ref)
            ref.playerId?.let { ticketsByUuid[it] = ticket }
            ticketsByName[ref.playerName] = ticket
        }
    }

    /** backupBeforeMatch 成功後に呼ぶ。 */
    fun register(refs: List<BackupRef>) {
        refs.forEach { ref ->
            val ticket = RestoreTicket(ref)
            ref.playerId?.let { ticketsByUuid[it] = ticket }
            ticketsByName[ref.playerName] = ticket
        }
    }

    /** UUID 優先、名前は uuid 一致(または uuid 無し)の場合のみ採用。 */
    fun ticketFor(playerId: UUID, playerName: String): RestoreTicket? =
        ticketsByUuid[playerId]
            ?: ticketsByName[playerName]?.takeIf { it.ref.playerId == null || it.ref.playerId == playerId }

    fun pending(playerId: UUID): RestoreTicket? = ticketsByUuid[playerId]

    private fun ownedBy(handle: PlayerHandle, ticket: RestoreTicket): Boolean =
        ticketsByUuid[handle.id] === ticket ||
            (ticketsByName[handle.name] === ticket &&
                (ticket.ref.playerId == null || ticket.ref.playerId == handle.id))

    /**
     * バックアップの復元を完結する。
     * ticket が null ならロビー転送だけを行い、持ち物には触れない。
     */
    fun restoreNow(handle: PlayerHandle, ticket: RestoreTicket?, respawn: Boolean, lobby: Boolean) {
        if (ticket == null) {
            if (lobby) teleportLobby(handle)
            return
        }
        if (!ownedBy(handle, ticket)) return
        if (respawn && handle.dead) handle.respawn()
        try {
            backups.restore(ticket.ref)
        } catch (e: PersistenceFailure) {
            failures.report("Could not restore inventory for ${handle.name} (${handle.id}); backup retained", e)
            return
        }
        presentation.clearScoreboard(handle.id)
        if (lobby) teleportLobby(handle)
        forget(ticket)
        try {
            backups.acknowledge(ticket.ref)
        } catch (e: PersistenceFailure) {
            // 削除失敗時はディスク上の記録が残る(次回起動で再復元=安全側)
            failures.report("Could not discard restored backup for ${handle.name} (${handle.id}); record retained", e)
        }
    }

    private fun forget(ticket: RestoreTicket) {
        ticketsByUuid.entries.removeIf { it.value === ticket }
        ticketsByName.entries.removeIf { it.value === ticket }
    }

    private fun teleportLobby(handle: PlayerHandle) {
        val lobby = lobby.lobby()
        if (lobby == null) {
            failures.warn("Lobby is not set; skipping teleport for ${handle.name}")
            return
        }
        handle.teleport(lobby)
    }

    /**
     * 停止処理。将来の scheduler 実行に依存せずオンライン分を同期復元する。
     * 死者は復元+スコアボードクリアのみ(記録は残し、次回ログインで再度復元)。
     * オフライン等の未完了データは残す。
     */
    fun restoreAllOnline() {
        ticketsByUuid.toList().forEach { (id, ticket) ->
            val handle = players.handle(id) ?: return@forEach
            if (handle.dead) {
                try {
                    backups.restore(ticket.ref)
                } catch (e: PersistenceFailure) {
                    failures.report("Could not restore inventory for ${handle.name} (${handle.id}); backup retained", e)
                    return@forEach
                }
                presentation.clearScoreboard(id)
            } else {
                restoreNow(handle, ticket, respawn = false, lobby = false)
            }
        }
    }
}
