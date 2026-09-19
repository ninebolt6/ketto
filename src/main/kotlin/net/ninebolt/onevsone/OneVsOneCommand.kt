package net.ninebolt.onevsone

import org.bukkit.block.Sign
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.Player
import org.bukkit.plugin.IllegalPluginAccessException
import org.bukkit.plugin.java.JavaPlugin
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture

class OneVsOneCommand(
    private val plugin: JavaPlugin,
    private val service: ArenaService,
    private val messages: Messages,
    private val resolveOffline: (String) -> CompletableFuture<UUID> = { name ->
        CompletableFuture.supplyAsync { plugin.server.getOfflinePlayer(name).uniqueId }
    }
) : TabExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (args.isEmpty()) {
            messages.send(sender, messages.usageRoot)
            return true
        }
        when (args[0].lowercase(Locale.ROOT)) {
            "stats" -> handleStats(sender, args)
            "leave" -> {
                if (sender !is Player) {
                    messages.send(sender, messages.playerOnly)
                } else {
                    service.leave(sender)
                }
            }
            "setlobby" -> {
                if (!sender.isOp) {
                    messages.send(sender, messages.noPermission)
                } else if (sender !is Player) {
                    messages.send(sender, messages.playerOnly)
                } else {
                    service.setLobby(sender.location)
                    messages.send(sender, messages.lobbySet)
                }
            }
            "arena" -> handleArena(sender, args)
            else -> messages.send(sender, messages.usageRoot)
        }
        return true
    }

    private fun handleStats(sender: CommandSender, args: Array<String>) {
        if (sender !is Player) {
            messages.send(sender, messages.playerOnly)
            return
        }
        if (args.size < 2) {
            showStats(sender, sender.uniqueId)
            return
        }
        val online = plugin.server.getPlayerExact(args[1])
        if (online != null) {
            showStats(sender, online.uniqueId)
            return
        }
        val cached = plugin.server.getOfflinePlayerIfCached(args[1])
        if (cached != null) {
            showStats(sender, cached.uniqueId)
            return
        }
        resolveOffline(args[1]).handle { uuid, error ->
            try {
                if (plugin.isEnabled) {
                    plugin.server.scheduler.runTask(plugin, Runnable {
                        if (sender.isOnline) {
                            if (error != null) messages.send(sender, messages.noStats) else showStats(sender, uuid)
                        }
                    })
                }
            } catch (e: IllegalPluginAccessException) {
            }
            null
        }
    }

    private fun showStats(sender: CommandSender, uuid: UUID) {
        if (!service.store.statsExist(uuid)) {
            messages.send(sender, messages.noStats)
            return
        }
        val (win, lose) = service.store.readStats(uuid)
        messages.send(sender, messages.statWin(win))
        messages.send(sender, messages.statLose(lose))
        messages.send(sender, messages.statRatio(win, lose))
    }

    private fun handleArena(sender: CommandSender, args: Array<String>) {
        if (args.size < 2) {
            messages.send(sender, messages.usageArena)
            return
        }
        when (args[1].lowercase(Locale.ROOT)) {
            "info" -> arenaInfo(sender, args)
            "create" -> arenaCreate(sender, args)
            "remove" -> arenaRemove(sender, args)
            "setspawn1" -> arenaSetSpawn(sender, args, 1)
            "setspawn2" -> arenaSetSpawn(sender, args, 2)
            "enable" -> arenaEnable(sender, args)
            "disable" -> arenaDisable(sender, args)
            "setinv" -> arenaSetInv(sender, args)
            "setsign" -> arenaSetSign(sender, args)
            else -> messages.send(sender, messages.usageArena)
        }
    }

    private fun arenaInfo(sender: CommandSender, args: Array<String>) {
        if (args.size < 3) {
            messages.send(sender, messages.usageArena)
            return
        }
        val arena = service.arena(args[2])
        if (arena == null) {
            messages.send(sender, messages.noArena)
            return
        }
        messages.send(sender, messages.arenaHeader(arena.name))
        messages.send(sender, messages.arenaState(arena.state.display))
        if ((arena.state == ArenaState.ROUNDCOUNTDOWN || arena.state == ArenaState.INGAME) && arena.players.size == 2) {
            val p1 = arena.players[0]
            val p2 = arena.players[1]
            messages.send(sender, messages.versus(p1.name, p2.name))
            messages.send(sender, messages.winCount(arena.wins[p1.id] ?: 0, arena.wins[p2.id] ?: 0))
        }
    }

    private fun arenaCreate(sender: CommandSender, args: Array<String>) {
        if (!sender.isOp) {
            messages.send(sender, messages.noPermission)
            return
        }
        if (args.size != 3) {
            messages.send(sender, messages.usageCreate)
            return
        }
        if (!service.store.isValidArenaName(args[2])) {
            messages.send(sender, messages.usageCreate)
            return
        }
        if (!service.createArena(args[2])) {
            messages.send(sender, messages.arenaExists)
            return
        }
        messages.send(sender, messages.created(args[2]))
    }

    private fun arenaRemove(sender: CommandSender, args: Array<String>) {
        if (!sender.isOp) {
            messages.send(sender, messages.noPermission)
            return
        }
        if (args.size != 3) {
            messages.send(sender, messages.usageRemove)
            return
        }
        if (!service.removeArena(args[2])) {
            messages.send(sender, messages.noArena)
            return
        }
        messages.send(sender, messages.removed(args[2]))
    }

    private fun arenaSetSpawn(sender: CommandSender, args: Array<String>, number: Int) {
        if (!sender.isOp) {
            messages.send(sender, messages.noPermission)
            return
        }
        if (sender !is Player) {
            messages.send(sender, messages.playerOnly)
            return
        }
        val usage = if (number == 1) messages.usageSetSpawn1 else messages.usageSetSpawn2
        if (args.size != 3) {
            messages.send(sender, usage)
            return
        }
        val arena = service.arena(args[2])
        if (arena == null) {
            messages.send(sender, messages.noArena)
            return
        }
        service.setSpawn(arena, number, sender.location)
        messages.send(sender, if (number == 1) messages.spawn1Set(arena.name) else messages.spawn2Set(arena.name))
    }

    private fun arenaEnable(sender: CommandSender, args: Array<String>) {
        if (!sender.isOp) {
            messages.send(sender, messages.noPermission)
            return
        }
        if (args.size != 3) {
            messages.send(sender, messages.usageEnable)
            return
        }
        val arena = service.arena(args[2])
        if (arena == null) {
            messages.send(sender, messages.noArena)
            return
        }
        if (!service.enableArena(arena)) {
            messages.send(sender, messages.alreadyEnabled)
            return
        }
        messages.send(sender, messages.enabled(arena.name))
    }

    private fun arenaDisable(sender: CommandSender, args: Array<String>) {
        if (!sender.isOp) {
            messages.send(sender, messages.noPermission)
            return
        }
        if (args.size != 3) {
            messages.send(sender, messages.usageDisable)
            return
        }
        val arena = service.arena(args[2])
        if (arena == null) {
            messages.send(sender, messages.noArena)
            return
        }
        if (!service.disableArena(arena)) {
            messages.send(sender, messages.alreadyDisabled)
            return
        }
        messages.send(sender, messages.disabled(arena.name))
    }

    private fun arenaSetInv(sender: CommandSender, args: Array<String>) {
        if (!sender.isOp) {
            messages.send(sender, messages.noPermission)
            return
        }
        if (sender !is Player) {
            messages.send(sender, messages.playerOnly)
            return
        }
        if (args.size != 3) {
            messages.send(sender, messages.usageSetInv)
            return
        }
        val arena = service.arena(args[2])
        if (arena == null) {
            messages.send(sender, messages.noArena)
            return
        }
        service.setKit(arena, sender.inventory)
        messages.send(sender, messages.inventorySet(arena.name))
    }

    private fun arenaSetSign(sender: CommandSender, args: Array<String>) {
        if (!sender.isOp) {
            messages.send(sender, messages.noPermission)
            return
        }
        if (sender !is Player) {
            messages.send(sender, messages.playerOnly)
            return
        }
        if (args.size != 3) {
            messages.send(sender, messages.usageSetSign)
            return
        }
        val arena = service.arena(args[2])
        if (arena == null) {
            messages.send(sender, messages.noArena)
            return
        }
        val target = sender.getTargetBlockExact(10)
        if (target == null || target.state !is Sign) {
            messages.send(sender, messages.lookAtSign)
            return
        }
        val existing = service.signArenaName(
            target.world.name,
            target.x.toDouble(),
            target.y.toDouble(),
            target.z.toDouble()
        )
        if (existing != null && existing != arena.name) {
            messages.send(sender, messages.signTaken)
            return
        }
        service.setSign(arena, target.location)
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<String>
    ): List<String> {
        if (args.size == 1) {
            val subs = if (sender.isOp) {
                listOf("stats", "leave", "setlobby", "arena")
            } else {
                listOf("stats", "leave", "arena")
            }
            return subs.filter { it.startsWith(args[0].lowercase(Locale.ROOT)) }
        }
        if (args[0].lowercase(Locale.ROOT) == "arena") {
            if (args.size == 2) {
                val subs = if (sender.isOp) {
                    listOf("info", "create", "remove", "setspawn1", "setspawn2", "enable", "disable", "setInv", "setsign")
                } else {
                    listOf("info")
                }
                return subs.filter { it.lowercase(Locale.ROOT).startsWith(args[1].lowercase(Locale.ROOT)) }
            }
            if (args.size == 3) {
                return service.arenas.keys.filter { it.startsWith(args[2]) }
            }
        }
        return emptyList()
    }
}
