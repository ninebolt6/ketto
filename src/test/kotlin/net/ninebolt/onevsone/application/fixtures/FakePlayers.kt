package net.ninebolt.onevsone.application.fixtures

import net.ninebolt.onevsone.application.port.PlayerHandle
import net.ninebolt.onevsone.application.port.PlayerPort
import net.ninebolt.onevsone.domain.WorldPosition
import java.util.UUID

class FakePlayers : PlayerPort {
    class FakeHandle(
        override val id: UUID,
        override val name: String
    ) : PlayerHandle {
        override var online = true
        override var dead = false
        var quitting = false
        var positionValue: WorldPosition? = WorldPosition("world", 0.0, 64.0, 0.0)
        val teleports = mutableListOf<WorldPosition>()
        val events = mutableListOf<String>()

        override fun position(): WorldPosition? = positionValue
        override fun respawn() {
            if (dead) {
                dead = false
                events += "respawn"
            }
        }

        override fun resetVitals() {
            if (!dead) events += "vitals"
        }

        override fun prepareForMatch() {
            if (!dead) events += "prepare"
        }

        override fun teleport(position: WorldPosition) {
            teleports += position
            events += "teleport"
        }
    }

    val players = mutableMapOf<UUID, FakeHandle>()

    override fun handle(playerId: UUID): PlayerHandle? =
        players[playerId]?.takeIf { it.online || it.quitting }

    fun add(name: String, id: UUID = UUID.randomUUID()): FakeHandle =
        FakeHandle(id, name).also { players[id] = it }

    fun disconnect(handle: FakeHandle) {
        handle.online = false
    }

    /** QuitEvent 中の切断者解決を再現するスコープ。 */
    fun <R> quittingScope(handle: FakeHandle, block: () -> R): R {
        handle.quitting = true
        try {
            return block()
        } finally {
            handle.quitting = false
        }
    }
}
