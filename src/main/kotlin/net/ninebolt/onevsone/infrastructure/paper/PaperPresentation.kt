package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.application.port.FailureReporter
import net.ninebolt.onevsone.application.port.PresentationPort
import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.domain.ArenaMatch
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.BlockPosition
import net.ninebolt.onevsone.domain.WorldPosition
import net.ninebolt.onevsone.infrastructure.paper.message.Message
import net.ninebolt.onevsone.infrastructure.paper.message.Messenger
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
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

class PaperPresentation(
    private val server: Server,
    private val messenger: Messenger,
    private val failures: FailureReporter
) : PresentationPort {

    private fun player(id: Uuid): Player? = server.getPlayer(id.toJavaUuid())

    private fun pling(player: Player, pitch: Float) {
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, 5f, pitch)
    }

    override fun countdownTick(participantIds: List<Uuid>, secondsLeft: Int) {
        val message = Message.MatchTeleportIn(secondsLeft)
        participantIds.forEach { id ->
            player(id)?.let { p ->
                messenger.send(p, message)
                pling(p, 1f)
            }
        }
    }

    override fun roundCountdownTick(participantIds: List<Uuid>, secondsLeft: Int) {
        val message = Message.MatchStartIn(secondsLeft)
        participantIds.forEach { id ->
            player(id)?.let { p ->
                messenger.send(p, message)
                pling(p, 1f)
            }
        }
    }

    override fun matchStart(participantIds: List<Uuid>) {
        participantIds.forEach { id ->
            player(id)?.let { p ->
                pling(p, 2f)
                messenger.send(p, Message.MatchGameStart)
            }
        }
    }

    override fun roundStart(participantIds: List<Uuid>) {
        participantIds.forEach { id ->
            player(id)?.let { p ->
                pling(p, 2f)
                messenger.send(p, Message.MatchRoundStart)
            }
        }
    }

    override fun roundWon(participantIds: List<Uuid>, round: Int, winnerName: String) {
        val message = Message.MatchRoundWinner(round, winnerName)
        participantIds.forEach { id ->
            player(id)?.let { messenger.send(it, message) }
        }
    }

    override fun roundEndSound(position: WorldPosition) {
        val world = server.getWorld(position.world) ?: return
        world.playSound(Location(world, position.x, position.y, position.z), Sound.ENTITY_GENERIC_EXPLODE, 2f, 1f)
    }

    override fun champion(arena: Arena.Id, winnerName: String) {
        messenger.broadcast(server, Message.MatchChampion(arena.name, winnerName))
    }

    override fun championFirework(playerId: Uuid) {
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
            messenger.render(Message.ScoreboardTitle(match.arenaId.name))
        )
        objective.displaySlot = DisplaySlot.SIDEBAR
        match.participants.forEach { (id, name) ->
            val score = objective.getScore(name)
            score.customName(messenger.render(Message.ScoreboardEntry(name)))
            score.score = match.winsOf(id)
            player(id)?.scoreboard = board
        }
    }

    override fun clearScoreboard(playerId: Uuid) {
        val manager = server.scoreboardManager
        player(playerId)?.scoreboard = manager.newScoreboard
    }

    override fun updateSign(arena: Arena.Id, position: BlockPosition, state: ArenaState) {
        val world = server.getWorld(position.world)
        if (world == null) {
            failures.warn("Sign world '${position.world}' for arena ${arena.name} is not loaded")
            return
        }
        val blockState = world.getBlockAt(position.x, position.y, position.z).state
        if (blockState !is Sign) return
        val front = blockState.getSide(Side.FRONT)
        front.line(0, messenger.render(Message.SignTitle))
        front.line(1, messenger.render(Message.SignArena(arena.name)))
        front.line(2, messenger.render(if (state.isJoinable()) Message.SignJoin else Message.SignCannotJoin))
        front.line(3, messenger.render(Message.StateDisplay(state)))
        blockState.update()
    }
}
