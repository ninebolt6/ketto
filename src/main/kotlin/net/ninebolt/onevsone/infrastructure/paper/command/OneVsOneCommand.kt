package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.application.ArenaApplicationService
import net.ninebolt.onevsone.infrastructure.paper.Messages
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.plugin.java.JavaPlugin

/**
 * /1vs1 の TabExecutor。plugin.yml からの登録点はここだけで、
 * 実処理は CommandGroup 経由で各サブコマンドへルーティングする。
 */
class OneVsOneCommand(
    plugin: JavaPlugin,
    service: ArenaApplicationService,
    admin: ArenaAdministrationService,
    private val messages: Messages
) : TabExecutor {

    private val root = CommandGroup(messages.usageRoot, messages, mapOf(
        "stats" to StatsCommand(plugin, service, messages),
        "leave" to LeaveCommand(service, messages),
        "setlobby" to SetLobbyCommand(admin, messages),
        "arena" to CommandGroup(messages.usageArena, messages, mapOf(
            "info" to ArenaInfoCommand(service, admin, messages),
            "create" to ArenaCreateCommand(admin, messages),
            "remove" to ArenaRemoveCommand(admin, messages),
            "setspawn1" to ArenaSetSpawnCommand(1, admin, messages),
            "setspawn2" to ArenaSetSpawnCommand(2, admin, messages),
            "enable" to ArenaSetEnabledCommand(true, admin, messages),
            "disable" to ArenaSetEnabledCommand(false, admin, messages),
            "setInv" to ArenaSetInventoryCommand(admin, messages),
            "setsign" to ArenaSetSignCommand(admin, messages),
            "removesign" to ArenaRemoveSignCommand(admin, messages)
        ))
    ))

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        root.execute(sender, args.toList())?.let { messages.send(sender, it) }
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<String>
    ): List<String> = root.tabComplete(sender, args.toList())
}
