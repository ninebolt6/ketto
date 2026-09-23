package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.application.ArenaApplicationService
import net.ninebolt.onevsone.application.port.FailureReporter
import net.ninebolt.onevsone.application.port.PlayerPort
import net.ninebolt.onevsone.infrastructure.paper.Message
import net.ninebolt.onevsone.infrastructure.paper.Messenger
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
    private val messenger: Messenger
) : TabExecutor {

    private val root = run {
        val arenaInfo = ArenaInfoCommand(service, admin, messenger)
        CommandGroup(Message.UsageRoot, messenger, mapOf(
            "stats" to StatsCommand(service, players, failures, messenger),
            "leave" to LeaveCommand(service, messenger),
            "lobby" to CommandGroup(Message.UsageLobby, messenger, mapOf(
                "set" to SetLobbyCommand(admin, messenger)
            )),
            "arena" to ArenaGroup(
                Message.UsageArena, Message.UsageArenaOps, messenger, admin,
                ArenaCreateCommand(admin, messenger),
                ArenaScopedGroup(
                    Message.UsageArena, Message.UsageArenaOps, messenger, admin, mapOf(
                        "info" to arenaInfo,
                        "remove" to ArenaRemoveCommand(admin, messenger),
                        "enable" to ArenaSetEnabledCommand(true, admin, messenger),
                        "disable" to ArenaSetEnabledCommand(false, admin, messenger),
                        "spawn" to ArenaScopedGroup(
                            Message.CommandNoPermission, Message.UsageSpawn, messenger, admin, mapOf(
                                "set" to ArenaSpawnSetCommand(admin, messenger)
                            )
                        ),
                        "kit" to ArenaScopedGroup(
                            Message.CommandNoPermission, Message.UsageKit, messenger, admin, mapOf(
                                "set" to ArenaKitSetCommand(admin, messenger)
                            )
                        ),
                        "sign" to ArenaScopedGroup(
                            Message.CommandNoPermission, Message.UsageSign, messenger, admin, mapOf(
                                "set" to ArenaSignSetCommand(admin, messenger),
                                "remove" to ArenaSignRemoveCommand(admin, messenger)
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
