package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.application.ArenaApplicationService
import net.ninebolt.onevsone.application.port.FailureReporter
import net.ninebolt.onevsone.application.port.PlayerPort
import net.ninebolt.onevsone.infrastructure.paper.Messages
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor

/**
 * TabExecutor for /1vs1. This is the only registration point from plugin.yml;
 * actual work routes to each subcommand via CommandGroup/ArenaGroup.
 */
class OneVsOneCommand(
    service: ArenaApplicationService,
    admin: ArenaAdministrationService,
    players: PlayerPort,
    failures: FailureReporter,
    private val messages: Messages
) : TabExecutor {

    private val root = run {
        val arenaInfo = ArenaInfoCommand(service, admin, messages)
        CommandGroup(messages.usageRoot, messages, mapOf(
            "stats" to StatsCommand(service, players, failures, messages),
            "leave" to LeaveCommand(service, messages),
            "lobby" to CommandGroup(messages.usageLobby, messages, mapOf(
                "set" to SetLobbyCommand(admin, messages)
            )),
            "arena" to ArenaGroup(
                messages.usageArena, messages.usageArenaOps, messages, admin,
                ArenaCreateCommand(admin, messages),
                ArenaScopedGroup(
                    messages.usageArena, messages.usageArenaOps, messages, admin, mapOf(
                        "info" to arenaInfo,
                        "remove" to ArenaRemoveCommand(admin, messages),
                        "enable" to ArenaSetEnabledCommand(true, admin, messages),
                        "disable" to ArenaSetEnabledCommand(false, admin, messages),
                        "spawn" to ArenaScopedGroup(
                            messages.noPermission, messages.usageSpawn, messages, admin, mapOf(
                                "set" to ArenaSpawnSetCommand(admin, messages)
                            )
                        ),
                        "kit" to ArenaScopedGroup(
                            messages.noPermission, messages.usageKit, messages, admin, mapOf(
                                "set" to ArenaKitSetCommand(admin, messages)
                            )
                        ),
                        "sign" to ArenaScopedGroup(
                            messages.noPermission, messages.usageSign, messages, admin, mapOf(
                                "set" to ArenaSignSetCommand(admin, messages),
                                "remove" to ArenaSignRemoveCommand(admin, messages)
                            )
                        )
                    ),
                    defaultOp = arenaInfo
                )
            )
        ))
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        root.execute(sender, args.toList())
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<String>
    ): List<String> = root.tabComplete(sender, args.toList())
}
