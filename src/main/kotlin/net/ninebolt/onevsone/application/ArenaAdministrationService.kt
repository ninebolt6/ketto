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

    fun arena(name: String): Arena? = Arena.Id.of(name)?.let { registry.arena(it) }

    fun create(name: String): Boolean {
        val id = Arena.Id.of(name) ?: return false
        if (registry.arenaIds().any { it.name.equals(name, ignoreCase = true) }) return false
        val arena = Arena(id)
        registry.installArena(arena)
        arenas.save(arena)
        return true
    }

    fun remove(name: String): Boolean {
        val id = Arena.Id.of(name) ?: return false
        if (registry.arena(id) == null) return false
        progression.abort(id)
        registry.removeArena(id)
        arenas.delete(name)
        signs.clearSign(name)
        return true
    }

    fun setEnabled(name: String, enabled: Boolean): ToggleReply {
        val id = Arena.Id.of(name) ?: return ToggleReply.NotFound
        val arena = registry.arena(id) ?: return ToggleReply.NotFound
        if (arena.enabled == enabled) {
            return if (enabled) ToggleReply.AlreadyEnabled else ToggleReply.AlreadyDisabled
        }
        val updated = registry.updateArena(id) { it.copy(enabled = enabled) } ?: return ToggleReply.NotFound
        arenas.save(updated)
        if (!enabled) progression.abort(id)
        return ToggleReply.Changed
    }

    fun setSpawn(name: String, slot: Int, position: WorldPosition): Boolean {
        val id = Arena.Id.of(name) ?: return false
        val updated = registry.updateArena(id) {
            if (slot == 1) it.copy(spawn1 = position) else it.copy(spawn2 = position)
        } ?: return false
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
        signs.setSign(name, position)
        val state = registry.match(arena.id)?.state ?: return true
        presentation.updateSign(arena.id, state)
        return true
    }

    /** 看板登録だけを解除する。看板ブロック自体は残り、破壊可能になる。 */
    fun clearSign(name: String): Boolean {
        arena(name) ?: return false
        signs.clearSign(name)
        return true
    }
}
