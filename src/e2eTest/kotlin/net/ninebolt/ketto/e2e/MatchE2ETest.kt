package net.ninebolt.ketto.e2e

import net.ninebolt.ketto.e2e.fixtures.DuelScenario
import net.ninebolt.ketto.e2e.fixtures.PaperServer
import net.ninebolt.ketto.e2e.fixtures.PaperServerExtension
import net.ninebolt.ketto.e2e.fixtures.commandExpect
import net.ninebolt.ketto.e2e.fixtures.standAt
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(PaperServerExtension::class)
class MatchE2ETest {
    @Test
    fun `players complete a match and recover inventories at the lobby`(server: PaperServer) {
        val duel = DuelScenario(server)
        duel.bob.commandExpect("ketto arena create e2e", "command.unknown.argument")

        duel.joinAndStart()
        duel.aliceDefeatsBob()
        duel.awaitRestored(duel.alice, "minecraft:bread", count = 3)
        duel.awaitRestored(duel.bob, "minecraft:apple", count = 5)
        duel.assertStats(duel.alice, wins = 1, losses = 0)
        duel.assertStats(duel.bob, wins = 0, losses = 1)
        val japaneseViewer = server.bot("Viewer", locale = "ja_jp")
        japaneseViewer.commandExpect("ketto stats Alice", "Win: 1")
        japaneseViewer.awaitMessage(description = "Japanese W/L ratio label") { it.contains("W/L(比率): 1.00") }
        duel.assertJoinableAgain()
    }

    @Test
    fun `disconnect forfeits the match and reconnect restores the loser`(server: PaperServer) {
        val duel = DuelScenario(server)
        duel.joinAndStart()

        val victory = duel.alice.markMessages()
        duel.bob.disconnect()
        duel.alice.awaitMessage(after = victory) { it.contains("Alice won the match") }
        duel.awaitRestored(duel.alice, "minecraft:bread", count = 3)
        duel.assertStats(duel.alice, wins = 1, losses = 0)
        duel.reconnectBob()
        duel.awaitRestored(duel.bob, "minecraft:apple", count = 5)
        duel.assertStats(duel.bob, wins = 0, losses = 1)
        server.standAt(duel.bob, duel.lobby, facing = duel.sign)
        duel.assertJoinableAgain(duel.bob)
    }

    @Test
    fun `crashed matches recover inventories and allow a new match`(server: PaperServer) {
        val duel = DuelScenario(server)
        duel.joinAndStart()
        server.command("save-all flush")

        server.kill()
        server.start()
        duel.reconnectPlayers()
        duel.awaitRestored(duel.alice, "minecraft:bread", count = 3)
        duel.awaitRestored(duel.bob, "minecraft:apple", count = 5)

        server.standAt(duel.alice, duel.lobby, facing = duel.sign)
        server.standAt(duel.bob, duel.lobby, facing = duel.sign)
        duel.joinAndStart()
    }
}
