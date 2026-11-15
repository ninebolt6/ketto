package net.ninebolt.ketto.e2e.fixtures

import org.geysermc.mcprotocollib.network.packet.Packet
import org.geysermc.mcprotocollib.protocol.data.game.ClientCommand
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.InteractAction
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientCommandPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundInteractPacket

internal object BotProtocol {
    fun attack(entityId: Int): Packet = ServerboundInteractPacket(entityId, InteractAction.ATTACK, false)

    fun respawn(): Packet = ServerboundClientCommandPacket(ClientCommand.RESPAWN)
}
