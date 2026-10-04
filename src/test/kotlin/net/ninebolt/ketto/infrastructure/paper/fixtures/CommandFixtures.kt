package net.ninebolt.ketto.infrastructure.paper.fixtures

import org.bukkit.Server
import org.bukkit.command.CommandSender
import org.mockbukkit.mockbukkit.command.CommandSourceStackMock
import org.mockbukkit.mockbukkit.command.brigadier.PaperCommandsMock
import kotlin.uuid.Uuid
import kotlin.uuid.toKotlinUuid

internal fun TestEnv.runCommand(sender: CommandSender, vararg args: String) = server.dispatchCommand(sender, (listOf("ketto") + args).joinToString(" "))

// ServerMock.getCommandTabComplete routes through the legacy Command.tabComplete, which never reaches the
// Brigadier dispatcher that lifecycle commands register into, so complete against PaperCommandsMock directly
internal fun TestEnv.tabComplete(sender: CommandSender, vararg args: String): List<String> {
    if (server.commandMap.getCommand("ketto") == null) {
        server.dispatchCommand(server.consoleSender, "ketto")
    }
    val dispatcher = PaperCommandsMock.INSTANCE.getDispatcherInternal()
    val parsed = dispatcher.parse((listOf("ketto") + args).joinToString(" "), CommandSourceStackMock.from(sender))
    return dispatcher.getCompletionSuggestions(parsed).join().list.map { it.text }
}

internal fun TestEnv.opPlayer(name: String): ArenaPlayerMock = player(name).also { it.isOp = true }

// ServerMock carries the upstream @Deprecated on getOfflinePlayer(name) that Paper removed, so call it through the Server type
internal fun TestEnv.offlineId(name: String): Uuid = (server as Server).getOfflinePlayer(name).uniqueId.toKotlinUuid()
