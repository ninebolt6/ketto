package net.ninebolt.ketto.e2e.fixtures

import org.geysermc.mcprotocollib.network.packet.Packet
import org.geysermc.mcprotocollib.protocol.data.game.ClientCommand
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientCommandPacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundAttackPacket

internal object BotProtocol {
    fun attack(entityId: Int): Packet = ServerboundAttackPacket(entityId)

    fun respawn(): Packet = ServerboundClientCommandPacket(ClientCommand.PERFORM_RESPAWN)
}
