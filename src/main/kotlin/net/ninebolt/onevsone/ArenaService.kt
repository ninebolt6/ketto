package net.ninebolt.onevsone

import org.bukkit.Color
import org.bukkit.FireworkEffect
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.block.Sign
import org.bukkit.block.sign.Side
import org.bukkit.entity.EntityType
import org.bukkit.entity.Firework
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scoreboard.Criteria
import org.bukkit.scoreboard.DisplaySlot
import java.util.Locale
import java.util.UUID

class ArenaService(
    private val plugin: JavaPlugin,
    val store: YamlStore,
    val messages: Messages
) {
    val arenas = LinkedHashMap<String, Arena>()
    private val playerArena = mutableMapOf<UUID, Arena>()
    private val pendingByUuid = mutableMapOf<UUID, PendingRestore>()
    private val pendingByName = mutableMapOf<String, PendingRestore>()
    private val resolving = mutableSetOf<Arena>()

    val requiredWins: Int

    init {
        val configured = plugin.config.getInt("required-wins", 3)
        if (configured < 1) {
            plugin.logger.warning("required-wins must be >= 1 (was $configured); using 1")
        }
        requiredWins = configured.coerceAtLeast(1)
    }

    fun load() {
        val seen = mutableSetOf<String>()
        for (name in store.arenaNames()) {
            if (!store.isValidArenaName(name)) {
                plugin.logger.warning("Ignoring invalid arena name '$name' in arenalist.yml")
                continue
            }
            if (!seen.add(name.lowercase(Locale.ROOT))) {
                plugin.logger.warning("Ignoring duplicate arena name '$name' in arenalist.yml")
                continue
            }
            val arena = try {
                store.loadArena(name)
            } catch (e: IllegalStateException) {
                null
            }
            if (arena == null) {
                plugin.logger.warning("Arena '$name' could not be loaded; skipping")
                continue
            }
            arena.state = ArenaState.WAITING
            arena.players.clear()
            arena.wins.clear()
            arenas[name] = arena
            store.saveStatus(arena)
            updateSign(arena)
        }
        val pendings = try {
            store.pendingRestores()
        } catch (e: IllegalStateException) {
            plugin.logger.warning("players.yml is unreadable; pending restores unavailable this session")
            emptyList()
        }
        for (pending in pendings) {
            pendingByName[pending.name] = pending
            pending.uuid?.let { pendingByUuid[it] = pending }
        }
        try {
            store.clearRegistrations()
        } catch (e: IllegalStateException) {
            plugin.logger.warning("Could not clear stale players.yml registrations")
        }
    }

    fun shutdown() {
        for (arena in arenas.values) {
            arena.generation++
            arena.task?.cancel()
            arena.task = null
            resolving.remove(arena)
            for (participant in arena.players.toList()) {
                playerArena.remove(participant.id)
                queueRestore(participant)
            }
            arena.players.clear()
            arena.wins.clear()
            arena.state = ArenaState.WAITING
            store.saveStatus(arena)
        }
        for ((id, pending) in pendingByUuid.toList()) {
            val player = plugin.server.getPlayer(id) ?: continue
            if (player.isDead) {
                restore(player, pending.snapshot)
                clearScoreboard(player)
            } else {
                completeRestore(player, pending, respawn = false, lobby = false)
            }
        }
    }

    fun arenaOf(id: UUID): Arena? = playerArena[id]

    fun arena(name: String): Arena? = arenas[name]

    internal fun pendingOf(id: UUID): PendingRestore? = pendingByUuid[id]

    private fun pendingFor(player: Player): PendingRestore? =
        pendingByUuid[player.uniqueId]
            ?: pendingByName[player.name]?.takeIf { it.uuid == null || it.uuid == player.uniqueId }

    private fun queueRestore(participant: Participant): PendingRestore? {
        val snapshot = participant.snapshot
        if (snapshot == null) {
            try {
                store.unregisterParticipant(participant.name, discardSnapshot = false)
            } catch (e: IllegalStateException) {
                plugin.logger.warning("Could not unregister ${participant.name} from players.yml; membership record may be stale")
            }
            return null
        }
        val pending = PendingRestore(participant.name, participant.id, snapshot)
        pendingByUuid[participant.id] = pending
        pendingByName[participant.name] = pending
        try {
            store.unregisterParticipant(participant.name, discardSnapshot = false)
        } catch (e: IllegalStateException) {
            plugin.logger.warning("Could not unregister ${participant.name} from players.yml; pending restore retained in memory")
        }
        return pending
    }

    private fun completeRestore(player: Player, pending: PendingRestore?, respawn: Boolean, lobby: Boolean) {
        if (pending == null) {
            if (lobby) teleportLobby(player)
            return
        }
        val owned = pendingByUuid[player.uniqueId] === pending ||
            (pendingByName[player.name] === pending && (pending.uuid == null || pending.uuid == player.uniqueId))
        if (!owned) return
        if (respawn && player.isDead) player.spigot().respawn()
        restore(player, pending.snapshot)
        clearScoreboard(player)
        if (lobby) teleportLobby(player)
        pendingByUuid.entries.removeIf { it.value === pending }
        pendingByName.entries.removeIf { it.value === pending }
        store.discardPendingRestore(pending.name)
    }

    fun join(player: Player, arena: Arena) {
        if (playerArena.containsKey(player.uniqueId)) {
            messages.send(player, messages.alreadyJoined)
            return
        }
        if (!arena.enabled) {
            messages.send(player, messages.notEnabled)
            return
        }
        if ((arena.state != ArenaState.WAITING && arena.state != ArenaState.ONEMORE) || arena.players.size >= 2) {
            messages.send(player, messages.arenaInGame)
            return
        }
        pendingFor(player)?.let { pending ->
            if (player.isDead) {
                messages.send(player, messages.arenaInGame)
                return
            }
            completeRestore(player, pending, respawn = true, lobby = false)
        }
        val participant = Participant(player.uniqueId, player.name)
        store.registerParticipant(participant, arena.name)
        arena.players.add(participant)
        playerArena[player.uniqueId] = arena
        messages.send(player, messages.joined(arena.name))
        if (arena.players.size == 1) {
            arena.state = ArenaState.ONEMORE
            messages.send(player, messages.waitOneMore)
        } else {
            arena.state = ArenaState.COUNTDOWN
            startCountdown(arena, initial = true)
        }
        store.saveStatus(arena)
        updateSign(arena)
    }

    fun leave(player: Player) {
        val arena = playerArena[player.uniqueId]
        if (arena == null) {
            messages.send(player, messages.notJoined)
            return
        }
        if (arena.state != ArenaState.ONEMORE) {
            messages.send(player, messages.cannotLeave)
            return
        }
        val participant = arena.players.firstOrNull { it.id == player.uniqueId }
        arena.players.removeIf { it.id == player.uniqueId }
        playerArena.remove(player.uniqueId)
        arena.generation++
        arena.state = ArenaState.WAITING
        if (participant != null) {
            val pending = queueRestore(participant)
            completeRestore(player, pending, respawn = false, lobby = false)
        }
        store.saveStatus(arena)
        updateSign(arena)
        messages.send(player, messages.leftArena)
    }

    fun quit(player: Player) {
        val arena = playerArena[player.uniqueId]
        if (arena == null) {
            pendingFor(player)?.let { completeRestore(player, it, respawn = false, lobby = false) }
            return
        }
        val participant = arena.players.firstOrNull { it.id == player.uniqueId }
        if (participant == null) {
            playerArena.remove(player.uniqueId)
            return
        }
        if (arena.state == ArenaState.ONEMORE || arena.state == ArenaState.WAITING || arena.players.size < 2) {
            arena.players.remove(participant)
            playerArena.remove(player.uniqueId)
            arena.generation++
            arena.state = ArenaState.WAITING
            val pending = queueRestore(participant)
            completeRestore(player, pending, respawn = false, lobby = false)
            store.saveStatus(arena)
            updateSign(arena)
            return
        }
        val winner = arena.players.first { it.id != player.uniqueId }
        finish(arena, winner, participant, player, death = false, forfeit = true)
    }

    fun lose(player: Player, death: Boolean = false): Boolean {
        val arena = playerArena[player.uniqueId] ?: return false
        if (arena.state != ArenaState.INGAME && !(arena.state == ArenaState.ROUNDCOUNTDOWN && !death)) return false
        if (arena.players.size != 2) return false
        if (arena in resolving) return false
        val loser = arena.players.firstOrNull { it.id == player.uniqueId } ?: return false
        val winner = arena.players.first { it.id != player.uniqueId }
        resolving.add(arena)
        if ((arena.wins[winner.id] ?: 0) >= requiredWins - 1) {
            finish(arena, winner, loser, player, death, forfeit = false)
            resolving.remove(arena)
        } else {
            endRound(arena, winner, loser, player, death)
        }
        return true
    }

    fun requestRespawn(player: Player) {
        plugin.server.scheduler.runTask(plugin, Runnable {
            if (player.isDead) player.spigot().respawn()
        })
    }

    fun restorePending(player: Player) {
        if (playerArena.containsKey(player.uniqueId)) return
        val pending = pendingFor(player) ?: return
        completeRestore(player, pending, respawn = true, lobby = false)
    }

    fun abort(arena: Arena) {
        arena.generation++
        arena.task?.cancel()
        arena.task = null
        resolving.remove(arena)
        val pendings = arena.players.map { it to queueRestore(it) }
        for (participant in arena.players) playerArena.remove(participant.id)
        arena.players.clear()
        arena.wins.clear()
        arena.state = ArenaState.WAITING
        for ((participant, pending) in pendings) {
            val player = plugin.server.getPlayer(participant.id) ?: continue
            if (player.isDead) {
                if (pending != null) {
                    plugin.server.scheduler.runTask(plugin, Runnable {
                        if (pendingByUuid[participant.id] !== pending || !player.isOnline) return@Runnable
                        if (player.isDead) player.spigot().respawn()
                        completeRestore(player, pending, respawn = false, lobby = false)
                    })
                } else {
                    plugin.server.scheduler.runTask(plugin, Runnable {
                        if (player.isOnline && player.isDead) player.spigot().respawn()
                    })
                }
            } else {
                completeRestore(player, pending, respawn = false, lobby = false)
            }
        }
        store.saveStatus(arena)
        updateSign(arena)
    }

    fun createArena(name: String): Boolean {
        if (!store.isValidArenaName(name)) return false
        if (arenas.keys.any { it.equals(name, ignoreCase = true) }) return false
        val arena = Arena(name)
        arenas[name] = arena
        store.saveArena(arena)
        store.saveArenaNames(arenas.keys.toList())
        return true
    }

    fun removeArena(name: String): Boolean {
        val arena = arenas[name] ?: return false
        abort(arena)
        arenas.remove(name)
        store.saveArenaNames(arenas.keys.toList())
        store.deleteArena(name)
        store.clearSign(name)
        return true
    }

    fun enableArena(arena: Arena): Boolean {
        if (arena.enabled) return false
        arena.enabled = true
        store.saveArena(arena)
        return true
    }

    fun disableArena(arena: Arena): Boolean {
        if (!arena.enabled) return false
        arena.enabled = false
        store.saveArena(arena)
        abort(arena)
        return true
    }

    fun setSpawn(arena: Arena, number: Int, location: Location) {
        val world = location.world
        if (world == null) {
            plugin.logger.warning("Cannot set spawn for arena ${arena.name}: location has no world")
            return
        }
        val saved = SavedLocation(world.name, location.x, location.y, location.z, location.yaw, location.pitch)
        if (number == 1) arena.spawn1 = saved else arena.spawn2 = saved
        store.saveArena(arena)
    }

    fun setKit(arena: Arena, inventory: PlayerInventory) {
        arena.kit = InventorySnapshot.capture(inventory)
        store.saveArena(arena)
    }

    fun setLobby(location: Location) {
        val world = location.world ?: return
        store.setLobby(SavedLocation(world.name, location.x, location.y, location.z, location.yaw, location.pitch))
    }

    fun signArenaName(world: String, x: Double, y: Double, z: Double): String? =
        store.signOwner(world, x, y, z)

    fun setSign(arena: Arena, location: Location): Boolean {
        val world = location.world ?: return false
        store.setSign(arena.name, SavedLocation(world.name, location.x, location.y, location.z))
        updateSign(arena)
        return true
    }

    fun updateSign(arena: Arena) {
        val sign = store.signLocation(arena.name) ?: return
        val world = plugin.server.getWorld(sign.world)
        if (world == null) {
            plugin.logger.warning("Sign world '${sign.world}' for arena ${arena.name} is not loaded")
            return
        }
        val state = world.getBlockAt(sign.x.toInt(), sign.y.toInt(), sign.z.toInt()).state
        if (state !is Sign) return
        val front = state.getSide(Side.FRONT)
        front.line(0, messages.component(messages.signTitle))
        front.line(1, messages.component(messages.signArena(arena.name)))
        val joinable = arena.state == ArenaState.WAITING || arena.state == ArenaState.ONEMORE
        front.line(2, messages.component(if (joinable) messages.signJoin else messages.signCannotJoin))
        front.line(3, messages.component(arena.state.display))
        state.update()
    }

    private fun endRound(arena: Arena, winner: Participant, loser: Participant, loserPlayer: Player, death: Boolean) {
        arena.generation++
        val generation = arena.generation
        arena.task?.cancel()
        arena.task = null
        arena.wins[winner.id] = (arena.wins[winner.id] ?: 0) + 1
        arena.state = ArenaState.ROUNDCOUNTDOWN

        val winnerPlayer = plugin.server.getPlayer(winner.id)
        if (winnerPlayer != null) {
            resetPlayer(winnerPlayer)
            arena.kit.apply(winnerPlayer.inventory)
        }

        loserPlayer.location.let { it.world?.playSound(it, Sound.ENTITY_GENERIC_EXPLODE, 2f, 1f) }

        val round = arena.wins.values.sum()
        val roundMessage = messages.roundWinner(round, winner.name)
        winnerPlayer?.let { messages.send(it, roundMessage) }
        messages.send(loserPlayer, roundMessage)

        updateScoreboard(arena)

        if (death) {
            plugin.server.scheduler.runTask(plugin, Runnable {
                if (arena.generation != generation) return@Runnable
                if (playerArena[loser.id] !== arena) return@Runnable
                if (!loserPlayer.isOnline) return@Runnable
                if (loserPlayer.isDead) loserPlayer.spigot().respawn()
                resetPlayer(loserPlayer)
                arena.kit.apply(loserPlayer.inventory)
                teleportToSlot(arena, loser, loserPlayer)
                resolving.remove(arena)
            })
        } else {
            resetPlayer(loserPlayer)
            arena.kit.apply(loserPlayer.inventory)
            teleportToSlot(arena, loser, loserPlayer)
            plugin.server.scheduler.runTask(plugin, Runnable {
                if (arena.generation == generation) resolving.remove(arena)
            })
        }
        winnerPlayer?.let { teleportToSlot(arena, winner, it) }

        store.saveStatus(arena)
        updateSign(arena)
        startCountdown(arena, initial = false)
    }

    private fun finish(
        arena: Arena,
        winner: Participant,
        loser: Participant,
        loserPlayer: Player,
        death: Boolean,
        forfeit: Boolean
    ) {
        arena.generation++
        val generation = arena.generation
        arena.task?.cancel()
        arena.task = null
        resolving.remove(arena)
        arena.state = ArenaState.WAITING

        val winnerPending = queueRestore(winner)
        val loserPending = queueRestore(loser)
        arena.players.clear()
        arena.wins.clear()
        playerArena.remove(winner.id)
        playerArena.remove(loser.id)

        messages.broadcast(plugin.server, messages.champion(arena.name, winner.name))

        val winnerPlayer = plugin.server.getPlayer(winner.id)
        if (winnerPlayer != null && !winnerPlayer.isDead) {
            resetVitals(winnerPlayer)
            completeRestore(winnerPlayer, winnerPending, respawn = false, lobby = true)
            if (!forfeit) spawnFirework(winnerPlayer)
        } else if (winnerPlayer != null) {
            plugin.server.scheduler.runTask(plugin, Runnable {
                if (winnerPending != null) {
                    if (pendingByUuid[winner.id] !== winnerPending) return@Runnable
                } else if (arena.generation != generation || playerArena[winner.id] != null) {
                    return@Runnable
                }
                if (!winnerPlayer.isOnline) return@Runnable
                if (winnerPlayer.isDead) winnerPlayer.spigot().respawn()
                resetVitals(winnerPlayer)
                completeRestore(winnerPlayer, winnerPending, respawn = false, lobby = true)
                if (!forfeit) spawnFirework(winnerPlayer)
            })
        }

        if (death) {
            plugin.server.scheduler.runTask(plugin, Runnable {
                if (loserPending != null) {
                    if (pendingByUuid[loser.id] !== loserPending) return@Runnable
                } else if (arena.generation != generation || playerArena[loser.id] != null) {
                    return@Runnable
                }
                if (!loserPlayer.isOnline) return@Runnable
                if (loserPlayer.isDead) loserPlayer.spigot().respawn()
                resetVitals(loserPlayer)
                completeRestore(loserPlayer, loserPending, respawn = false, lobby = true)
            })
        } else {
            if (!forfeit) resetVitals(loserPlayer)
            completeRestore(loserPlayer, loserPending, respawn = false, lobby = !forfeit)
        }

        store.saveStatus(arena)
        updateSign(arena)
        recordResult(winner, loser)
    }

    private fun recordResult(winner: Participant, loser: Participant) {
        for ((participant, win) in listOf(winner to true, loser to false)) {
            try {
                if (win) store.addWin(participant.id) else store.addLose(participant.id)
            } catch (e: IllegalStateException) {
                plugin.logger.log(java.util.logging.Level.SEVERE, "Failed to record ${if (win) "win" else "loss"} for ${participant.name} (${participant.id}); arena cleanup completed, statistics require manual recovery", e)
            }
        }
    }

    private fun startCountdown(arena: Arena, initial: Boolean) {
        val expected = if (initial) ArenaState.COUNTDOWN else ArenaState.ROUNDCOUNTDOWN
        val generation = arena.generation
        var remaining = if (initial) 5 else 7
        arena.task = object : BukkitRunnable() {
            override fun run() {
                if (arena.generation != generation || arena.state != expected || arena.players.size != 2) {
                    cancel()
                    return
                }
                val first = arena.players[0]
                val second = arena.players[1]
                val p1 = plugin.server.getPlayer(first.id)
                val p2 = plugin.server.getPlayer(second.id)
                if (p1 == null || p2 == null) {
                    cancel()
                    abort(arena)
                    return
                }
                if (initial) {
                    if (remaining > 0) {
                        val message = messages.teleportIn(remaining)
                        messages.send(p1, message)
                        messages.send(p2, message)
                        p1.playSound(p1.location, Sound.BLOCK_NOTE_BLOCK_PLING, 5f, 1f)
                        p2.playSound(p2.location, Sound.BLOCK_NOTE_BLOCK_PLING, 5f, 1f)
                    } else {
                        if (p1.isDead || p2.isDead) return
                        val snapshots = listOf(
                            first to InventorySnapshot.capture(p1.inventory),
                            second to InventorySnapshot.capture(p2.inventory)
                        )
                        try {
                            store.saveSnapshots(snapshots)
                        } catch (e: IllegalStateException) {
                            plugin.logger.log(java.util.logging.Level.SEVERE, "Could not save inventories before starting arena ${arena.name}; match aborted", e)
                            cancel()
                            abort(arena)
                            return
                        }
                        for ((participant, snapshot) in snapshots) participant.snapshot = snapshot
                        arena.kit.apply(p1.inventory)
                        arena.kit.apply(p2.inventory)
                        resetPlayer(p1)
                        resetPlayer(p2)
                        teleportToSlot(arena, first, p1)
                        teleportToSlot(arena, second, p2)
                        p1.playSound(p1.location, Sound.BLOCK_NOTE_BLOCK_PLING, 5f, 2f)
                        p2.playSound(p2.location, Sound.BLOCK_NOTE_BLOCK_PLING, 5f, 2f)
                        messages.send(p1, messages.gameStart)
                        messages.send(p2, messages.gameStart)
                        arena.state = ArenaState.INGAME
                        updateScoreboard(arena)
                        store.saveStatus(arena)
                        updateSign(arena)
                        cancel()
                    }
                } else {
                    when (remaining) {
                        7 -> {
                            arena.kit.apply(p1.inventory)
                            arena.kit.apply(p2.inventory)
                            resetPlayer(p1)
                            resetPlayer(p2)
                        }
                        in 1..5 -> {
                            val message = messages.startIn(remaining)
                            messages.send(p1, message)
                            messages.send(p2, message)
                            p1.playSound(p1.location, Sound.BLOCK_NOTE_BLOCK_PLING, 5f, 1f)
                            p2.playSound(p2.location, Sound.BLOCK_NOTE_BLOCK_PLING, 5f, 1f)
                        }
                        0 -> {
                            p1.playSound(p1.location, Sound.BLOCK_NOTE_BLOCK_PLING, 5f, 2f)
                            p2.playSound(p2.location, Sound.BLOCK_NOTE_BLOCK_PLING, 5f, 2f)
                            messages.send(p1, messages.roundStart)
                            messages.send(p2, messages.roundStart)
                            arena.state = ArenaState.INGAME
                            resolving.remove(arena)
                            store.saveStatus(arena)
                            updateSign(arena)
                            cancel()
                        }
                        else -> {}
                    }
                }
                remaining--
            }
        }.runTaskTimer(plugin, 10L, 20L)
    }

    private fun resetVitals(player: Player) {
        if (player.isDead) return
        player.fireTicks = 0
        player.health = minOf(20.0, player.getAttribute(Attribute.GENERIC_MAX_HEALTH)?.value ?: 20.0)
        player.foodLevel = 20
    }

    private fun resetPlayer(player: Player) {
        player.gameMode = GameMode.SURVIVAL
        player.allowFlight = false
        resetVitals(player)
    }

    private fun restore(player: Player, snapshot: InventorySnapshot) {
        player.inventory.clear()
        if (snapshot.isEmpty) {
            giveLobbyItems(player)
        } else {
            snapshot.apply(player.inventory)
        }
    }

    private fun giveLobbyItems(player: Player) {
        val compass = ItemStack(Material.COMPASS)
        plugin.server.itemFactory.getItemMeta(Material.COMPASS)?.let { meta ->
            meta.displayName(messages.component(messages.compassName))
            compass.itemMeta = meta
        }
        val feather = ItemStack(Material.FEATHER)
        plugin.server.itemFactory.getItemMeta(Material.FEATHER)?.let { meta ->
            meta.displayName(messages.component(messages.featherName))
            feather.itemMeta = meta
        }
        player.inventory.setItem(0, compass)
        player.inventory.setItem(8, feather)
    }

    private fun resolve(location: SavedLocation): Location? {
        val world = plugin.server.getWorld(location.world)
        if (world == null) {
            plugin.logger.warning("World '${location.world}' is not loaded; skipping teleport")
            return null
        }
        return Location(world, location.x, location.y, location.z, location.yaw, location.pitch)
    }

    private fun teleportToSlot(arena: Arena, participant: Participant, player: Player) {
        val index = arena.players.indexOfFirst { it.id == participant.id }
        if (index < 0) return
        val saved = if (index == 0) arena.spawn1 else arena.spawn2
        if (saved == null) {
            plugin.logger.warning("Arena ${arena.name} spawn ${index + 1} is not set; skipping teleport")
            return
        }
        resolve(saved)?.let { player.teleport(it) }
    }

    private fun teleportLobby(player: Player) {
        val lobby = store.lobby()
        if (lobby == null) {
            plugin.logger.warning("Lobby is not set; skipping teleport for ${player.name}")
            return
        }
        resolve(lobby)?.let { player.teleport(it) }
    }

    private fun updateScoreboard(arena: Arena) {
        val manager = plugin.server.scoreboardManager
        val board = manager.newScoreboard
        val objective = board.registerNewObjective("1vs1", Criteria.DUMMY, messages.component(messages.scoreboardTitle(arena.name)))
        objective.displaySlot = DisplaySlot.SIDEBAR
        for (participant in arena.players) {
            objective.getScore(messages.scoreboardEntry(participant.name)).score = arena.wins[participant.id] ?: 0
        }
        for (participant in arena.players) {
            plugin.server.getPlayer(participant.id)?.scoreboard = board
        }
    }

    private fun clearScoreboard(player: Player) {
        player.scoreboard = plugin.server.scoreboardManager.newScoreboard
    }

    private fun spawnFirework(player: Player) {
        val firework = player.world.spawnEntity(player.location, EntityType.FIREWORK_ROCKET) as? Firework ?: return
        val meta = firework.fireworkMeta
        meta.power = 1
        meta.addEffect(
            FireworkEffect.builder()
                .withColor(Color.RED)
                .withFade(Color.BLUE)
                .with(FireworkEffect.Type.CREEPER)
                .build()
        )
        firework.fireworkMeta = meta
    }
}
