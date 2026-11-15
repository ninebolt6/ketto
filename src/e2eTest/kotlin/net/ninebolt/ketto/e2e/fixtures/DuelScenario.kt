package net.ninebolt.ketto.e2e.fixtures

import org.geysermc.mcprotocollib.protocol.data.game.entity.`object`.Direction

class DuelScenario(val server: PaperServer) {
    val lobby = StandingSpot(0.5, 64.0, 3.5)
    val firstSpawn = StandingSpot(10.5, 64.0, 0.5)
    val secondSpawn = StandingSpot(12.0, 64.0, 0.5)
    val sign = SignBlock(0, 65, 0)
    val admin: Bot
    var alice: Bot
        private set
    var bob: Bot
        private set

    init {
        server.configurePlugin("language: auto\ndefault-language: en\nrequired-wins: 1\n")
        server.start()
        admin = server.bot("Admin")
        server.command("op Admin")
        server.command("fill -5 63 -5 16 63 8 minecraft:stone")
        server.standAt(admin, lobby)
        admin.commandExpect("ketto lobby set", "Lobby location set")
        admin.commandExpect("ketto arena create e2e", "Created arena: e2e")
        server.standAt(admin, firstSpawn)
        admin.commandExpect("ketto arena e2e spawn set 1", "Set spawn 1 for arena: e2e")
        server.standAt(admin, secondSpawn)
        admin.commandExpect("ketto arena e2e spawn set 2", "Set spawn 2 for arena: e2e")
        server.give(admin, "minecraft:diamond_sword")
        admin.commandExpect("ketto arena e2e kit set", "Set kit for arena: e2e")
        server.placeSign(sign)
        server.standAt(admin, lobby, facing = sign)
        admin.command("ketto arena e2e sign set")
        server.awaitSign(sign, "e2e")
        admin.commandExpect("ketto arena e2e enable", "Enabled arena: e2e")
        server.awaitSign(sign, "Join", "Waiting")
        alice = server.bot("Alice")
        bob = server.bot("Bob")
        server.standAt(alice, lobby, facing = sign)
        server.standAt(bob, lobby, facing = sign)
        server.give(alice, "minecraft:bread", 3)
        server.give(bob, "minecraft:apple", 5)
    }

    fun joinAndStart() {
        val aliceJoin = alice.markMessages()
        alice.useBlock(sign.x, sign.y, sign.z, Direction.SOUTH)
        alice.awaitMessage(after = aliceJoin) { it.contains("Joined arena: e2e") }
        alice.awaitMessage { it.contains("Waiting for one more player") }
        val bobJoin = bob.markMessages()
        bob.useBlock(sign.x, sign.y, sign.z, Direction.SOUTH)
        bob.awaitMessage(after = bobJoin) { it.contains("Joined arena: e2e") }
        alice.awaitMessage { it.contains("Teleporting in:") }
        server.awaitSign(sign, "Countdown", "Cannot join")
        alice.awaitMessage { it.contains("Game start!") }
        bob.awaitMessage { it.contains("Game start!") }
        server.awaitItem(alice, "weapon.mainhand", "minecraft:diamond_sword")
        server.awaitItem(bob, "weapon.mainhand", "minecraft:diamond_sword")
        server.awaitNoItem(alice, "minecraft:bread")
        server.awaitNoItem(bob, "minecraft:apple")
        server.awaitPosition(alice, firstSpawn)
        server.awaitPosition(bob, secondSpawn)
    }

    fun aliceDefeatsBob() {
        val victory = alice.markMessages()
        repeat(12) {
            alice.attack(bob)
            server.waitTicks(20)
            if (alice.messagesSince(victory).any { it.contains("Alice won the match") }) {
                alice.awaitMessage(after = victory) { it.contains("Alice won the match") }
                return
            }
        }
        error("Alice did not defeat Bob after twelve attacks: ${alice.messages}\n${server.logTail()}")
    }

    fun awaitRestored(bot: Bot, item: String, count: Int) {
        awaitInventoryRestored(bot, item, count)
        server.awaitPosition(bot, lobby)
    }

    fun awaitInventoryRestored(bot: Bot, item: String, count: Int) {
        server.awaitItem(bot, "hotbar.0", "$item[count=$count]")
        server.awaitNoItem(bot, "minecraft:diamond_sword")
    }

    fun assertStats(bot: Bot, wins: Int, losses: Int) {
        bot.commandExpect("ketto stats", "Win: $wins")
        bot.awaitMessage(description = "loss count $losses") { it.contains("Lose: $losses") }
    }

    fun reconnectBob() {
        bob = bob.reconnect()
    }

    fun reconnectPlayers() {
        alice = alice.reconnect()
        reconnectBob()
    }

    fun assertJoinableAgain(player: Bot = alice) {
        server.awaitSign(sign, "Join", "Waiting")
        val joined = player.markMessages()
        player.useBlock(sign.x, sign.y, sign.z, Direction.SOUTH)
        player.awaitMessage(after = joined) { it.contains("Joined arena: e2e") }
        player.commandExpect("ketto leave", "You left the arena")
        server.awaitSign(sign, "Join", "Waiting")
    }
}
