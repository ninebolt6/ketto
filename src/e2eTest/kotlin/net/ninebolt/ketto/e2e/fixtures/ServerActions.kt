package net.ninebolt.ketto.e2e.fixtures

import java.sql.DriverManager
import java.time.Duration
import kotlin.math.abs

data class StandingSpot(val x: Double, val y: Double, val z: Double)

data class SignBlock(val x: Int, val y: Int, val z: Int)

fun PaperServer.waitTicks(ticks: Int) {
    require(ticks >= 0)
    fun gameTime(): Long {
        val response = command("time query gametime")
        return checkNotNull(Regex("\\d+").find(response)) { response }.value.toLong()
    }
    val start = gameTime()
    await("$ticks game ticks", Duration.ofSeconds(20 + ticks / 10L)) { gameTime() - start >= ticks }
}

fun PaperServer.playerPosition(bot: Bot): StandingSpot {
    val response = command("data get entity ${bot.name} Pos")
    val coordinates = response.substringAfter('[').substringBefore(']').split(',').map { it.trim().removeSuffix("d").toDouble() }
    check(coordinates.size == 3) { response }
    return StandingSpot(coordinates[0], coordinates[1], coordinates[2])
}

fun PaperServer.awaitPosition(bot: Bot, expected: StandingSpot) {
    await("${bot.name} at $expected") {
        val actual = playerPosition(bot)
        abs(actual.x - expected.x) < 0.1 && abs(actual.y - expected.y) < 0.1 && abs(actual.z - expected.z) < 0.1
    }
}

fun PaperServer.standAt(bot: Bot, spot: StandingSpot, facing: SignBlock? = null) {
    val direction = facing?.let { " facing ${it.x + 0.5} ${it.y + 0.5} ${it.z + 0.5}" } ?: ""
    command("execute anchored eyes run tp ${bot.name} ${spot.x} ${spot.y} ${spot.z}$direction")
    awaitPosition(bot, spot)
    bot.await("teleport to $spot") {
        bot.position?.let { abs(it.x - spot.x) < 0.1 && abs(it.y - spot.y) < 0.1 && abs(it.z - spot.z) < 0.1 } == true
    }
    waitTicks(2)
}

fun PaperServer.give(bot: Bot, item: String, count: Int = 1) {
    command("give ${bot.name} $item $count")
    awaitItem(bot, "hotbar.0", item)
}

fun PaperServer.awaitItem(bot: Bot, slot: String, item: String) {
    await("${bot.name} has $item in $slot") {
        command("execute if items entity ${bot.name} $slot $item").contains("Test passed")
    }
}

fun PaperServer.awaitNoItem(bot: Bot, item: String) {
    await("${bot.name} has no $item") {
        command("execute unless items entity ${bot.name} inventory.* $item").contains("Test passed") &&
            command("execute unless items entity ${bot.name} hotbar.* $item").contains("Test passed")
    }
}

fun PaperServer.awaitGameMode(bot: Bot, gameMode: String) {
    await("${bot.name} is $gameMode") {
        command("execute if entity @a[name=${bot.name},gamemode=$gameMode]").contains("Test passed")
    }
}

fun PaperServer.awaitFlight(bot: Bot, allowed: Boolean) {
    val serialized = if (allowed) "1b" else "0b"
    await("${bot.name} flight allowed is $allowed") {
        command("data get entity ${bot.name} abilities.mayfly").contains(serialized)
    }
}

fun PaperServer.placeSign(sign: SignBlock) {
    command("setblock ${sign.x} ${sign.y} ${sign.z - 1} minecraft:stone")
    command("setblock ${sign.x} ${sign.y} ${sign.z} minecraft:oak_wall_sign[facing=south]")
}

fun PaperServer.awaitSign(sign: SignBlock, vararg text: String) {
    await("sign contains ${text.toList()}") {
        val response = (1..3).joinToString("\n") { line ->
            command("data get block ${sign.x} ${sign.y} ${sign.z} front_text.messages[$line]")
        }
        text.all(response::contains)
    }
}

fun PaperServer.markArenaWorldMissing(world: String) {
    DriverManager.getConnection("jdbc:sqlite:${pluginDirectory.resolve("data.db")}").use { connection ->
        connection.prepareStatement("UPDATE arenas SET spawn1_world = ?, spawn2_world = ? WHERE name = 'e2e'").use { statement ->
            statement.setString(1, world)
            statement.setString(2, world)
            check(statement.executeUpdate() == 1)
        }
    }
}

fun Bot.commandExpect(command: String, message: String) {
    val reply = markMessages()
    command(command)
    awaitMessage(after = reply, description = "reply containing '$message'") { it.contains(message) }
}
