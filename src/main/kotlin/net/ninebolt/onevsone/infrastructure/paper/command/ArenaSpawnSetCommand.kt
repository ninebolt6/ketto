package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.domain.SpawnSlot
import net.ninebolt.onevsone.infrastructure.paper.message.Message
import net.ninebolt.onevsone.infrastructure.paper.message.Messenger
import net.ninebolt.onevsone.infrastructure.paper.toWorldPosition
import org.bukkit.entity.Player

internal class ArenaSpawnSetCommand(
    private val administration: ArenaAdministrationService,
    private val messenger: Messenger,
) {

    fun execute(player: Player, arenaName: String, slot: SpawnSlot) {
        val id = administration.findArenaId(arenaName) ?: run {
            messenger.send(player, Message.ArenaNotFound)
            return
        }
        administration.setSpawn(id, slot, player.toWorldPosition())
        messenger.send(player, Message.ArenaSpawnSet(id.name, slot.number))
    }
}
