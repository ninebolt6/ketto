package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.port.ArenaRepository
import net.ninebolt.onevsone.application.port.ArenaSignRepository
import net.ninebolt.onevsone.application.port.KitPort
import net.ninebolt.onevsone.application.port.LobbyRepository
import net.ninebolt.onevsone.application.port.MatchPresentationPort
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.WorldPosition
import kotlin.uuid.Uuid

/**
 * create/remove/enable/disable、スポーン・装備・看板・ロビー設定の管理操作。
 * 必要な中断は MatchProgressionService へ依頼する。
 */
class ArenaAdministrationService(
    private val registry: ArenaRegistry,
    private val arenas: ArenaRepository,
    private val signs: ArenaSignRepository,
    private val lobby: LobbyRepository,
    private val kit: KitPort,
    private val presentation: MatchPresentationPort,
    private val progression: MatchProgressionService
) {
    /** 登録順の arena 名一覧(タブ補完用)。 */
    fun arenaNames(): List<String> = registry.arenaIds().map { it.name }

    fun arena(name: String): Arena? = registry.resolveArenaId(name)?.let { registry.arena(it) }

    fun create(name: String): Boolean {
        val id = Arena.Id.of(name) ?: return false
        if (registry.resolveArenaId(name) != null) return false
        val arena = Arena.new(id)
        registry.installArena(arena)
        arenas.save(arena)
        return true
    }

    fun remove(name: String): Boolean {
        val arena = arena(name) ?: return false
        progression.abort(arena.id)
        registry.removeArena(arena.id)
        // 解決後の正規名で消す(大小文字違いの入力でもファイルと看板登録を残さない)
        arenas.delete(arena.name)
        signs.clearSign(arena.name)
        kit.forgetKit(arena.id)
        return true
    }

    fun setEnabled(name: String, enabled: Boolean): ToggleReply {
        val id = registry.resolveArenaId(name) ?: return ToggleReply.NotFound
        val arena = registry.arena(id) ?: return ToggleReply.NotFound
        if (arena.enabled == enabled) {
            return if (enabled) ToggleReply.AlreadyEnabled else ToggleReply.AlreadyDisabled
        }
        val updated = registry.updateArena(id) { if (enabled) it.enable() else it.disable() }
            ?: return ToggleReply.NotFound
        arenas.save(updated)
        if (!enabled) progression.abort(id)
        return ToggleReply.Changed
    }

    fun setSpawn(name: String, slot: Int, position: WorldPosition): Boolean {
        val id = registry.resolveArenaId(name) ?: return false
        val updated = registry.updateArena(id) { it.withSpawn(slot - 1, position) } ?: return false
        arenas.save(updated)
        return true
    }

    /** 実行者の現在装備をアリーナ装備として保存する。 */
    fun setKit(name: String, playerId: Uuid): Boolean {
        val arena = arena(name) ?: return false
        kit.saveKit(arena.id, playerId)
        return true
    }

    fun setLobby(position: WorldPosition) {
        lobby.setLobby(position)
    }

    fun signLocation(arenaName: String): WorldPosition? = signs.signLocation(arenaName)

    fun signOwner(world: String, x: Int, y: Int, z: Int): String? =
        signs.signOwner(world, x, y, z)

    fun setSign(name: String, position: WorldPosition): Boolean {
        val arena = arena(name) ?: return false
        signs.setSign(arena.name, position)
        val state = registry.match(arena.id)?.state ?: return true
        presentation.updateSign(arena.id, state)
        return true
    }

    /** 看板登録だけを解除する。看板ブロック自体は残り、破壊可能になる。 */
    fun clearSign(name: String): Boolean {
        val arena = arena(name) ?: return false
        signs.clearSign(arena.name)
        return true
    }
}
