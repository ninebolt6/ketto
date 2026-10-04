package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.infrastructure.paper.message.Message
import net.ninebolt.onevsone.infrastructure.paper.message.Messenger
import org.bukkit.entity.Player
import kotlin.uuid.toKotlinUuid

internal class ArenaKitSetCommand(
    private val administration: ArenaAdministrationService,
    private val messenger: Messenger,
) {

    fun execute(player: Player, arenaName: String) {
        val id = administration.resolveArenaId(arenaName) ?: run {
            messenger.send(player, Message.ArenaNotFound)
            return
        }
        administration.setKit(id, player.uniqueId.toKotlinUuid())
        messenger.send(player, Message.ArenaKitSet(id.name))
    }
}
