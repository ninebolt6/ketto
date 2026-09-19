package net.ninebolt.onevsone.application

import net.ninebolt.onevsone.application.port.ArenaRepository
import net.ninebolt.onevsone.application.port.MatchPresentationPort
import net.ninebolt.onevsone.application.port.PlayerEquipmentPort
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
    private val equipment: PlayerEquipmentPort,
    private val presentation: MatchPresentationPort,
    private val matches: ArenaApplicationService
) {
    fun isValidName(name: String): Boolean = isValidArenaName(name)

    /** 登録順の arena 名一覧(タブ補完用)。 */
    fun arenaNames(): List<String> = registry.definitions.keys.map { it.name }

    fun definition(name: String): ArenaDefinition? = registry.definitions[ArenaId(name)]

    fun create(name: String): Boolean {
        if (!isValidArenaName(name)) return false
        if (registry.definitions.keys.any { it.name.equals(name, ignoreCase = true) }) return false
        val definition = ArenaDefinition(ArenaId(name))
        registry.definitions[definition.id] = definition
        registry.matches[definition.id] = ArenaMatch(definition.id, matches.requiredWins)
        arenas.save(definition)
        arenas.saveArenaNames(arenaNames())
        return true
    }

    fun remove(name: String): Boolean {
        val id = ArenaId(name)
        if (!registry.definitions.containsKey(id)) return false
        matches.abort(id)
        registry.definitions.remove(id)
        registry.matches.remove(id)
        arenas.saveArenaNames(arenaNames())
        arenas.delete(name)
        arenas.clearSign(name)
        return true
    }

    fun setEnabled(name: String, enabled: Boolean): ToggleReply {
        val definition = registry.definitions[ArenaId(name)] ?: return ToggleReply.NotFound
        if (definition.enabled == enabled) {
            return if (enabled) ToggleReply.AlreadyEnabled else ToggleReply.AlreadyDisabled
        }
        definition.enabled = enabled
        arenas.save(definition)
        if (!enabled) matches.abort(definition.id)
        return ToggleReply.Changed
    }

    fun setSpawn(name: String, slot: Int, position: WorldPosition): Boolean {
        val definition = registry.definitions[ArenaId(name)] ?: return false
        if (slot == 1) definition.spawn1 = position else definition.spawn2 = position
        arenas.save(definition)
        return true
    }

    /** 実行者の現在装備をアリーナ装備として保存する。 */
    fun setKit(name: String, playerId: UUID): Boolean {
        val definition = registry.definitions[ArenaId(name)] ?: return false
        equipment.saveKit(definition.id, playerId)
        return true
    }

    fun setLobby(position: WorldPosition) {
        arenas.setLobby(position)
    }

    fun signLocation(arenaName: String): WorldPosition? = arenas.signLocation(arenaName)

    fun signOwner(world: String, x: Double, y: Double, z: Double): String? =
        arenas.signOwner(world, x, y, z)

    fun setSign(name: String, position: WorldPosition): Boolean {
        val definition = registry.definitions[ArenaId(name)] ?: return false
        arenas.setSign(name, position)
        presentation.updateSign(definition.id, registry.matches[definition.id]!!.state())
        return true
    }
}
