package net.ninebolt.onevsone.application.port

import net.ninebolt.onevsone.domain.WorldPosition
import kotlin.uuid.Uuid

interface PlayerPort {
    // also returns disconnecting players, which the adapter registers while processing QuitEvent
    fun findHandle(playerId: Uuid): PlayerHandle?

    // the callback runs on the main thread, never fires after plugin disable, and must re-check the player is still around
    fun resolveOfflineId(name: String, callback: (Uuid?) -> Unit)
}

interface PlayerHandle {
    val id: Uuid
    val name: String
    val online: Boolean
    val dead: Boolean
    fun position(): WorldPosition
    fun respawn()

    // no-op while dead
    fun resetVitals()
    fun prepareForMatch()

    // failures (e.g. an unloaded world) only warn in the adapter
    fun teleport(position: WorldPosition)
}
