package net.ninebolt.onevsone.infrastructure.paper.fixtures

import org.bukkit.Server
import org.bukkit.command.CommandSender
import kotlin.uuid.Uuid
import kotlin.uuid.toKotlinUuid

internal fun TestEnv.runCommand(sender: CommandSender, vararg args: String) = server.dispatchCommand(sender, (listOf("1vs1") + args).joinToString(" "))

// getCommandTabComplete does not trigger lifecycle initialization, so dispatch once first
internal fun TestEnv.tabComplete(sender: CommandSender, vararg args: String): List<String> {
    if (server.commandMap.getCommand("1vs1") == null) {
        server.dispatchCommand(server.consoleSender, "1vs1")
    }
    return server.getCommandTabComplete(sender, (listOf("1vs1") + args).joinToString(" "))
}

internal fun TestEnv.writeStats(uuid: Uuid, win: Int, lose: Int) {
    repeat(win) { statsRepo.recordWin(uuid) }
    repeat(lose) { statsRepo.recordLoss(uuid) }
}

internal fun TestEnv.opPlayer(name: String): ArenaPlayerMock = player(name).also { it.isOp = true }

// ServerMock carries the upstream @Deprecated on getOfflinePlayer(name) that Paper removed, so call it through the Server type
internal fun TestEnv.offlineId(name: String): Uuid = (server as Server).getOfflinePlayer(name).uniqueId.toKotlinUuid()
