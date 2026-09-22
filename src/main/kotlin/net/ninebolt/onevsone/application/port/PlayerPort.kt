package net.ninebolt.onevsone.application.port

import net.ninebolt.onevsone.domain.WorldPosition
import kotlin.uuid.Uuid

/**
 * Limited operations on online (or disconnecting) players.
 * Equipment and display are not included. No entity references are returned;
 * callers resolve by UUID to check liveness/position and request match-state
 * changes, teleports, and respawns.
 */
interface PlayerPort {
    /**
     * Operation handle for a player. Returns online players and disconnecting
     * players the adapter registered while processing QuitEvent. null
     * otherwise.
     */
    fun handle(playerId: Uuid): PlayerHandle?

    /**
     * Resolves a UUID from a name. Immediate for online/cached players;
     * uncached names go through blocking resolution offloaded to the adapter's
     * async path. callback is invoked on the main thread and is not invoked
     * after the plugin is disabled. The callback must check whether the target
     * player is still around.
     */
    fun resolveOfflineId(name: String, callback: (Uuid?) -> Unit)
}

interface PlayerHandle {
    val id: Uuid
    val name: String
    val online: Boolean
    val dead: Boolean
    fun position(): WorldPosition?
    /** Respawns immediately if dead. */
    fun respawn()
    /** Fire ticks 0, full health, food level 20. Does nothing while dead. */
    fun resetVitals()
    /** SURVIVAL, no flight, plus resetVitals. */
    fun prepareForMatch()
    /** The adapter warns on failure such as an unloaded world. */
    fun teleport(position: WorldPosition)
}
