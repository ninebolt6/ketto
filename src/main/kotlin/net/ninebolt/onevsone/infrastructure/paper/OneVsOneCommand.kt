package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.application.ArenaAdministrationService
import net.ninebolt.onevsone.application.ArenaApplicationService
import net.ninebolt.onevsone.application.LeaveReply
import net.ninebolt.onevsone.application.ToggleReply
import net.ninebolt.onevsone.domain.ArenaState
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

/**
 * /1vs1 コマンドの入力アダプター。権限・引数・位置の変換に限定し、
 * 業務処理は application サービスのみ呼ぶ。
 */
class OneVsOneCommand(
    private val plugin: JavaPlugin,
    private val service: ArenaApplicationService,
    private val admin: ArenaAdministrationService,
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
                    when (service.leave(sender.uniqueId)) {
                        LeaveReply.Left -> messages.send(sender, messages.leftArena)
                        LeaveReply.NotWaiting -> messages.send(sender, messages.cannotLeave)
                        LeaveReply.NotJoined -> messages.send(sender, messages.notJoined)
                    }
                }
            }
            "setlobby" -> {
                if (!sender.isOp) {
                    messages.send(sender, messages.noPermission)
                } else if (sender !is Player) {
                    messages.send(sender, messages.playerOnly)
                } else {
                    val position = sender.location.toWorldPosition()
                    if (position != null) {
                        admin.setLobby(position)
                        messages.send(sender, messages.lobbySet)
                    }
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
        // 破損した stats は PersistenceFailure として伝播する
        val stats = service.statsFor(uuid)
        if (stats == null) {
            messages.send(sender, messages.noStats)
            return
        }
        messages.send(sender, messages.statWin(stats.wins))
        messages.send(sender, messages.statLose(stats.losses))
        messages.send(sender, messages.statRatio(stats))
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
        val view = service.matchView(args[2])
        if (view == null) {
            messages.send(sender, messages.noArena)
            return
        }
        messages.send(sender, messages.arenaHeader(view.arenaId.name))
        messages.send(sender, messages.arenaState(messages.stateDisplay(view.state)))
        if ((view.state == ArenaState.ROUNDCOUNTDOWN || view.state == ArenaState.INGAME) &&
            view.participants.size == 2
        ) {
            val p1 = view.participants[0]
            val p2 = view.participants[1]
            messages.send(sender, messages.versus(p1.name, p2.name))
            messages.send(sender, messages.winCount(view.winsOf(p1.id), view.winsOf(p2.id)))
        }
    }

    private fun arenaCreate(sender: CommandSender, args: Array<String>) {
        if (!sender.isOp) {
            messages.send(sender, messages.noPermission)
            return
        }
        if (args.size != 3 || !admin.isValidName(args[2])) {
            messages.send(sender, messages.usageCreate)
            return
        }
        if (!admin.create(args[2])) {
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
        if (!admin.remove(args[2])) {
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
        val definition = admin.definition(args[2])
        if (definition == null) {
            messages.send(sender, messages.noArena)
            return
        }
        // world 無しの位置は保存をスキップするが、応答は従来通り成功メッセージ
        sender.location.toWorldPosition()?.let { admin.setSpawn(definition.name, number, it) }
        messages.send(sender, if (number == 1) messages.spawn1Set(definition.name) else messages.spawn2Set(definition.name))
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
        when (admin.setEnabled(args[2], true)) {
            ToggleReply.NotFound -> messages.send(sender, messages.noArena)
            ToggleReply.AlreadyEnabled -> messages.send(sender, messages.alreadyEnabled)
            else -> messages.send(sender, messages.enabled(args[2]))
        }
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
        when (admin.setEnabled(args[2], false)) {
            ToggleReply.NotFound -> messages.send(sender, messages.noArena)
            ToggleReply.AlreadyDisabled -> messages.send(sender, messages.alreadyDisabled)
            else -> messages.send(sender, messages.disabled(args[2]))
        }
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
        val definition = admin.definition(args[2])
        if (definition == null) {
            messages.send(sender, messages.noArena)
            return
        }
        admin.setKit(definition.name, sender.uniqueId)
        messages.send(sender, messages.inventorySet(definition.name))
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
        val definition = admin.definition(args[2])
        if (definition == null) {
            messages.send(sender, messages.noArena)
            return
        }
        val target = sender.getTargetBlockExact(10)
        if (target == null || target.state !is Sign) {
            messages.send(sender, messages.lookAtSign)
            return
        }
        val existing = admin.signOwner(
            target.world.name,
            target.x.toDouble(),
            target.y.toDouble(),
            target.z.toDouble()
        )
        if (existing != null && existing != definition.name) {
            messages.send(sender, messages.signTaken)
            return
        }
        target.location.toWorldPosition()?.let { admin.setSign(definition.name, it) }
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
                return admin.arenaNames().filter { it.startsWith(args[2]) }
            }
        }
        return emptyList()
    }
}
