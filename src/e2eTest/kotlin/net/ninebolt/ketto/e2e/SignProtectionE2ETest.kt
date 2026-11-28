package net.ninebolt.ketto.e2e

import net.ninebolt.ketto.e2e.fixtures.DuelScenario
import net.ninebolt.ketto.e2e.fixtures.PaperServer
import net.ninebolt.ketto.e2e.fixtures.PaperServerExtension
import net.ninebolt.ketto.e2e.fixtures.SignBlock
import net.ninebolt.ketto.e2e.fixtures.StandingSpot
import net.ninebolt.ketto.e2e.fixtures.awaitBlock
import net.ninebolt.ketto.e2e.fixtures.standAt
import net.ninebolt.ketto.e2e.fixtures.waitTicks
import org.geysermc.mcprotocollib.protocol.data.game.entity.`object`.Direction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(PaperServerExtension::class)
class SignProtectionE2ETest {
    @Test
    fun `registered join sign survives indirect destruction`(server: PaperServer) {
        val duel = DuelScenario(server)
        val breaker = server.bot("Breaker")
        server.command("gamemode creative Breaker")

        server.standAt(breaker, StandingSpot(0.5, 64.0, 1.5), facing = SignBlock(0, 65, -1))
        breaker.dig(0, 65, -1, Direction.SOUTH)
        server.awaitBlock(0, 65, -1, "minecraft:air")
        server.waitTicks(4)
        server.awaitBlock(duel.sign.x, duel.sign.y, duel.sign.z, "minecraft:oak_wall_sign")

        duel.assertJoinableAgain(breaker)
    }
}
