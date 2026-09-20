package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.port.ArenaRepository
import net.ninebolt.onevsone.application.port.ArenaSignRepository
import net.ninebolt.onevsone.application.port.KitPort
import net.ninebolt.onevsone.application.port.LobbyRepository
import net.ninebolt.onevsone.application.port.MatchPresentationPort
import net.ninebolt.onevsone.domain.ArenaDefinition
import net.ninebolt.onevsone.domain.ArenaId
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.WorldPosition
import net.ninebolt.onevsone.domain.isValidArenaName
import java.util.UUID

/**
 * create/remove/enable/disable、スポーン・装備・看板・ロビー設定の管理操作。
 * 必要な中断は ArenaApplicationService へ依頼する。
 */
class ArenaAdministrationService(
    private val registry: ArenaRegistry,
    private val arenas: ArenaRepository,
    private val signs: ArenaSignRepository,
    private val lobby: LobbyRepository,
    private val kit: KitPort,
    private val presentation: MatchPresentationPort,
    private val matches: ArenaApplicationService
) {
    fun isValidName(name: String): Boolean = isValidArenaName(name)

    /** 登録順の arena 名一覧(タブ補完用)。 */
    fun arenaNames(): List<String> = registry.definitionIds().map { it.name }

    fun definition(name: String): ArenaDefinition? = registry.definition(ArenaId(name))

    fun create(name: String): Boolean {
        if (!isValidArenaName(name)) return false
        if (registry.definitionIds().any { it.name.equals(name, ignoreCase = true) }) return false
        val definition = ArenaDefinition(ArenaId(name))
        registry.putDefinition(definition)
        registry.installMatch(ArenaMatch(definition.id, matches.requiredWins))
        arenas.save(definition)
        return true
    }

    fun remove(name: String): Boolean {
        val id = ArenaId(name)
        if (registry.definition(id) == null) return false
        matches.abort(id)
        registry.removeDefinition(id)
        registry.removeMatch(id)
        arenas.delete(name)
        signs.clearSign(name)
        return true
    }

    fun setEnabled(name: String, enabled: Boolean): ToggleReply {
        val definition = registry.definition(ArenaId(name)) ?: return ToggleReply.NotFound
        if (definition.enabled == enabled) {
            return if (enabled) ToggleReply.AlreadyEnabled else ToggleReply.AlreadyDisabled
        }
        val updated = definition.copy(enabled = enabled)
        registry.putDefinition(updated)
        arenas.save(updated)
        if (!enabled) matches.abort(definition.id)
        return ToggleReply.Changed
    }

    fun setSpawn(name: String, slot: Int, position: WorldPosition): Boolean {
        val definition = registry.definition(ArenaId(name)) ?: return false
        val updated = if (slot == 1) {
            definition.copy(spawn1 = position)
        } else {
            definition.copy(spawn2 = position)
        }
        registry.putDefinition(updated)
        arenas.save(updated)
        return true
    }

    /** 実行者の現在装備をアリーナ装備として保存する。 */
    fun setKit(name: String, playerId: UUID): Boolean {
        val definition = registry.definition(ArenaId(name)) ?: return false
        kit.saveKit(definition.id, playerId)
        return true
    }

    fun setLobby(position: WorldPosition) {
        lobby.setLobby(position)
    }

    fun signLocation(arenaName: String): WorldPosition? = signs.signLocation(arenaName)

    fun signOwner(world: String, x: Int, y: Int, z: Int): String? =
        signs.signOwner(world, x, y, z)

    fun setSign(name: String, position: WorldPosition): Boolean {
        val definition = registry.definition(ArenaId(name)) ?: return false
        signs.setSign(name, position)
        val state = registry.match(definition.id)?.state ?: return true
        presentation.updateSign(definition.id, state)
        return true
    }

    /** 看板登録だけを解除する。看板ブロック自体は残り、破壊可能になる。 */
    fun clearSign(name: String): Boolean {
        registry.definition(ArenaId(name)) ?: return false
        signs.clearSign(name)
        return true
    }
}
