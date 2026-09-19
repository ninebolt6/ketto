package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.application.port.ArenaSignRepository
import net.ninebolt.onevsone.application.port.FailureReporter
import net.ninebolt.onevsone.application.port.MatchPresentationPort
import net.ninebolt.onevsone.domain.ArenaId
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.WorldPosition
import org.bukkit.Color
import org.bukkit.FireworkEffect
import org.bukkit.Location
import org.bukkit.Server
import org.bukkit.Sound
import org.bukkit.block.Sign
import org.bukkit.block.sign.Side
import org.bukkit.entity.EntityType
import org.bukkit.entity.Firework
import org.bukkit.entity.Player
import org.bukkit.scoreboard.Criteria
import org.bukkit.scoreboard.DisplaySlot
import java.util.UUID

class PaperMatchPresentation(
    private val server: Server,
    private val messages: Messages,
    private val signs: ArenaSignRepository,
    private val failures: FailureReporter
) : MatchPresentationPort {

    private fun player(id: UUID): Player? = server.getPlayer(id)

    private fun pling(player: Player, pitch: Float) {
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, 5f, pitch)
    }

    override fun countdownTick(participantIds: List<UUID>, secondsLeft: Int) {
        val message = messages.teleportIn(secondsLeft)
        participantIds.forEach { id ->
            player(id)?.let { p ->
                messages.send(p, message)
                pling(p, 1f)
            }
        }
    }

    override fun roundCountdownTick(participantIds: List<UUID>, secondsLeft: Int) {
        val message = messages.startIn(secondsLeft)
        participantIds.forEach { id ->
            player(id)?.let { p ->
                messages.send(p, message)
                pling(p, 1f)
            }
        }
    }

    override fun matchStart(participantIds: List<UUID>) {
        participantIds.forEach { id ->
            player(id)?.let { p ->
                pling(p, 2f)
                messages.send(p, messages.gameStart)
            }
        }
    }

    override fun roundStart(participantIds: List<UUID>) {
        participantIds.forEach { id ->
            player(id)?.let { p ->
                pling(p, 2f)
                messages.send(p, messages.roundStart)
            }
        }
    }

    override fun roundWon(participantIds: List<UUID>, round: Int, winnerName: String) {
        val message = messages.roundWinner(round, winnerName)
        participantIds.forEach { id ->
            player(id)?.let { messages.send(it, message) }
        }
    }

    override fun roundEndSound(position: WorldPosition) {
        val world = server.getWorld(position.world) ?: return
        world.playSound(Location(world, position.x, position.y, position.z), Sound.ENTITY_GENERIC_EXPLODE, 2f, 1f)
    }

    override fun champion(arena: ArenaId, winnerName: String) {
        messages.broadcast(server, messages.champion(arena.name, winnerName))
    }

    override fun championFirework(playerId: UUID) {
        val player = player(playerId) ?: return
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

    override fun updateScoreboard(match: ArenaMatch) {
        val manager = server.scoreboardManager
        val board = manager.newScoreboard
        val objective = board.registerNewObjective(
            "1vs1",
            Criteria.DUMMY,
            messages.component(messages.scoreboardTitle(match.arenaId.name))
        )
        objective.displaySlot = DisplaySlot.SIDEBAR
        match.participants.forEach { (id, name) ->
            objective.getScore(messages.scoreboardEntry(name)).score = match.winsOf(id)
        }
        match.participants.forEach { (id) ->
            player(id)?.scoreboard = board
        }
    }

    override fun clearScoreboard(playerId: UUID) {
        val manager = server.scoreboardManager
        player(playerId)?.scoreboard = manager.newScoreboard
    }

    override fun updateSign(arena: ArenaId, state: ArenaState) {
        val sign = signs.signLocation(arena.name) ?: return
        val world = server.getWorld(sign.world)
        if (world == null) {
            failures.warn("Sign world '${sign.world}' for arena ${arena.name} is not loaded")
            return
        }
        val blockState = world.getBlockAt(sign.x.toInt(), sign.y.toInt(), sign.z.toInt()).state
        if (blockState !is Sign) return
        val front = blockState.getSide(Side.FRONT)
        front.line(0, messages.component(messages.signTitle))
        front.line(1, messages.component(messages.signArena(arena.name)))
        front.line(2, messages.component(if (state.isJoinable()) messages.signJoin else messages.signCannotJoin))
        front.line(3, messages.component(messages.stateDisplay(state)))
        blockState.update()
    }
}
