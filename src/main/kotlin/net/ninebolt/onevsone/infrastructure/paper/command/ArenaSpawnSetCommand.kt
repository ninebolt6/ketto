package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.domain.SpawnSlot
import net.ninebolt.onevsone.infrastructure.paper.message.Message
import net.ninebolt.onevsone.infrastructure.paper.message.Messenger
import net.ninebolt.onevsone.infrastructure.paper.toWorldPosition
import org.bukkit.entity.Player

internal class ArenaSpawnSetCommand(
    private val admin: ArenaAdministrationService,
    private val messenger: Messenger,
) {

    fun execute(player: Player, arenaName: String, slotNumber: Int) {
        val slot = SpawnSlot.ofNumber(slotNumber) ?: return
        val id = admin.resolveArenaId(arenaName) ?: run {
            messenger.send(player, Message.ArenaNotFound)
            return
        }
        admin.setSpawn(id, slot, player.toWorldPosition())
        messenger.send(player, Message.ArenaSpawnSet(id.name, slot.number))
    }
}
