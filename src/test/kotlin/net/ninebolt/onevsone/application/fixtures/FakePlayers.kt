package net.ninebolt.onevsone.application.fixtures

import net.ninebolt.onevsone.application.port.PlayerHandle
import net.ninebolt.onevsone.application.port.PlayerPort
import net.ninebolt.onevsone.domain.WorldPosition
import kotlin.uuid.Uuid

class FakePlayers : PlayerPort {
    class FakeHandle(
        override val id: Uuid,
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

    val players = mutableMapOf<Uuid, FakeHandle>()
    val offlineIds = mutableMapOf<String, Uuid>()

    override fun handle(playerId: Uuid): PlayerHandle? =
        players[playerId]?.takeIf { it.online || it.quitting }

    override fun resolveOfflineId(name: String, callback: (Uuid?) -> Unit) {
        callback(offlineIds[name])
    }

    fun add(name: String, id: Uuid = Uuid.random()): FakeHandle =
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
