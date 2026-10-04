package net.ninebolt.onevsone.infrastructure.paper.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.application.ArenaSignService
import net.ninebolt.onevsone.application.LobbyService
import net.ninebolt.onevsone.application.MatchParticipationService
import net.ninebolt.onevsone.application.PlayerStatsService
import net.ninebolt.onevsone.domain.SpawnSlot
import net.ninebolt.onevsone.infrastructure.paper.message.Message
import net.ninebolt.onevsone.infrastructure.paper.message.Messenger
import org.bukkit.entity.Player
import java.util.function.Predicate

internal const val ADMIN_PERMISSION = "1vs1.admin"

class OneVsOneCommand(
    participation: MatchParticipationService,
    private val admin: ArenaAdministrationService,
    statsService: PlayerStatsService,
    signs: ArenaSignService,
    lobby: LobbyService,
    private val messenger: Messenger,
) {

    private val stats = StatsCommand(statsService, messenger)
    private val leave = LeaveCommand(participation, messenger)
    private val lobbySet = LobbySetCommand(lobby, messenger)
    private val create = ArenaCreateCommand(admin, messenger)
    private val info = ArenaInfoCommand(participation, messenger)
    private val remove = ArenaRemoveCommand(admin, messenger)
    private val enable = ArenaEnableCommand(admin, messenger)
    private val disable = ArenaDisableCommand(admin, messenger)
    private val spawnSet = ArenaSpawnSetCommand(admin, messenger)
    private val kitSet = ArenaKitSetCommand(admin, messenger)
    private val signSet = ArenaSignSetCommand(admin, signs, messenger)
    private val signRemove = ArenaSignRemoveCommand(admin, signs, messenger)

    private val adminOnly = Predicate<CommandSourceStack> { it.sender.hasPermission(ADMIN_PERMISSION) }

    fun node(): LiteralCommandNode<CommandSourceStack> = Commands.literal("1vs1")
        .executes(send(Message.UsageRoot))
        .then(
            Commands.literal("stats")
                .executes(playerOnly { player, _ -> stats.execute(player, null) })
                .then(
                    Commands.argument("player", StringArgumentType.word())
                        .executes(playerOnly { player, ctx -> stats.execute(player, StringArgumentType.getString(ctx, "player")) }),
                ),
        )
        .then(Commands.literal("leave").executes(playerOnly { player, _ -> leave.execute(player) }))
        .then(
            Commands.literal("lobby").requires(adminOnly)
                .executes(send(Message.UsageLobby))
                .then(Commands.literal("set").executes(playerOnly { player, _ -> lobbySet.execute(player) })),
        )
        .then(arenaNode())
        .build()

    private fun arenaNode() = Commands.literal("arena")
        .executes(
            exec { ctx ->
                messenger.send(ctx.source.sender, if (adminOnly.test(ctx.source)) Message.UsageArenaOps else Message.UsageArena)
            },
        )
        .then(
            Commands.literal("create").requires(adminOnly)
                .executes(send(Message.UsageCreate))
                .then(
                    Commands.argument("arena", ArenaNameArgumentType)
                        .executes(exec { ctx -> create.execute(ctx.source.sender, ctx.arenaName()) }),
                ),
        )
        .then(
            Commands.argument("arena", ArenaNameArgumentType)
                .suggests { _, builder ->
                    suggestArenaNames(builder)
                    builder.buildFuture()
                }
                .executes(exec { ctx -> info.execute(ctx.source.sender, ctx.arenaName()) })
                .then(
                    Commands.literal("remove").requires(adminOnly)
                        .executes(exec { ctx -> remove.execute(ctx.source.sender, ctx.arenaName()) }),
                )
                .then(
                    Commands.literal("enable").requires(adminOnly)
                        .executes(exec { ctx -> enable.execute(ctx.source.sender, ctx.arenaName()) }),
                )
                .then(
                    Commands.literal("disable").requires(adminOnly)
                        .executes(exec { ctx -> disable.execute(ctx.source.sender, ctx.arenaName()) }),
                )
                .then(
                    Commands.literal("spawn").requires(adminOnly)
                        .executes(send(Message.UsageSpawn))
                        .then(
                            Commands.literal("set")
                                .executes(send(Message.UsageSpawn))
                                .then(
                                    Commands.argument("slot", IntegerArgumentType.integer(1, SpawnSlot.entries.size))
                                        .suggests { _, builder ->
                                            suggestSlots(builder)
                                            builder.buildFuture()
                                        }
                                        .executes(
                                            playerOnly { player, ctx ->
                                                spawnSet.execute(player, ctx.arenaName(), SpawnSlot.entries[IntegerArgumentType.getInteger(ctx, "slot") - 1])
                                            },
                                        ),
                                ),
                        ),
                )
                .then(
                    Commands.literal("kit").requires(adminOnly)
                        .executes(send(Message.UsageKit))
                        .then(
                            Commands.literal("set")
                                .executes(playerOnly { player, ctx -> kitSet.execute(player, ctx.arenaName()) }),
                        ),
                )
                .then(
                    Commands.literal("sign").requires(adminOnly)
                        .executes(send(Message.UsageSign))
                        .then(
                            Commands.literal("set")
                                .executes(playerOnly { player, ctx -> signSet.execute(player, ctx.arenaName()) }),
                        )
                        .then(
                            Commands.literal("remove")
                                .executes(exec { ctx -> signRemove.execute(ctx.source.sender, ctx.arenaName()) }),
                        ),
                ),
        )

    private fun CommandContext<CommandSourceStack>.arenaName(): String = StringArgumentType.getString(this, "arena")

    private fun send(message: Message): Command<CommandSourceStack> = Command { ctx ->
        messenger.send(ctx.source.sender, message)
        Command.SINGLE_SUCCESS
    }

    private fun exec(block: (CommandContext<CommandSourceStack>) -> Unit): Command<CommandSourceStack> = Command { ctx ->
        block(ctx)
        Command.SINGLE_SUCCESS
    }

    private fun playerOnly(block: (Player, CommandContext<CommandSourceStack>) -> Unit): Command<CommandSourceStack> = Command { ctx ->
        val sender = ctx.source.sender
        if (sender is Player) block(sender, ctx) else messenger.send(sender, Message.CommandPlayerOnly)
        Command.SINGLE_SUCCESS
    }

    private fun suggestArenaNames(builder: SuggestionsBuilder) = admin.arenaNames()
        .filter { it.startsWith(builder.remaining, ignoreCase = true) }
        .forEach(builder::suggest)

    private fun suggestSlots(builder: SuggestionsBuilder) = SpawnSlot.entries
        .map { it.number.toString() }
        .filter { it.startsWith(builder.remaining) }
        .forEach(builder::suggest)
}
