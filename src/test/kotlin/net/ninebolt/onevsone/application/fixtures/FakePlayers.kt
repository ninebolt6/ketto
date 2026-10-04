package net.ninebolt.onevsone.application.fixtures

import net.ninebolt.onevsone.application.port.PlayerHandle
import net.ninebolt.onevsone.application.port.PlayerPort
import net.ninebolt.onevsone.domain.WorldPosition
import kotlin.uuid.Uuid

class FakePlayers : PlayerPort {
    class FakeHandle(
        override val id: Uuid,
        override val name: String,
    ) : PlayerHandle {
        override var online = true
        override var dead = false
        var quitting = false
        var positionValue: WorldPosition = WorldPosition.new("world", 0.0, 64.0, 0.0)
        val teleports = mutableListOf<WorldPosition>()
        val events = mutableListOf<String>()
        var onTeleport: (() -> Unit)? = null
        var onRespawn: (() -> Unit)? = null

        override fun position(): WorldPosition = positionValue
        override fun respawn() {
            if (dead) {
                dead = false
                events += "respawn"
                onRespawn?.invoke()
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
            onTeleport?.invoke()
        }
    }

    val players = mutableMapOf<Uuid, FakeHandle>()
    val offlineIds = mutableMapOf<String, Uuid>()

    override fun findHandle(playerId: Uuid): PlayerHandle? = players[playerId]?.takeIf { it.online || it.quitting }

    override fun resolveOfflineId(name: String, callback: (Uuid?) -> Unit) {
        callback(offlineIds[name])
    }

    fun add(name: String, id: Uuid = Uuid.random()): FakeHandle = FakeHandle(id, name).also { players[id] = it }

    fun disconnect(handle: FakeHandle) {
        handle.online = false
    }

    fun <R> quittingScope(handle: FakeHandle, block: () -> R): R {
        handle.quitting = true
        try {
            return block()
        } finally {
            handle.quitting = false
        }
    }
}
