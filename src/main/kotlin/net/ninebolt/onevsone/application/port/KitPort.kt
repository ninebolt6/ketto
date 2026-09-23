package net.ninebolt.onevsone.application.port

import net.ninebolt.onevsone.domain.Arena
import kotlin.uuid.Uuid

/**
 * Applying and saving the arena equipment (kit). ItemStack payloads stay
 * inside infrastructure.
 */
interface KitPort {
    fun applyKit(arena: Arena.Id, playerId: Uuid)

    /** Saves the player's current equipment as the arena kit (kit set). */
    fun saveKit(arena: Arena.Id, playerId: Uuid)

    /** Discards the held kit when the arena is removed, so recreating it under the same name does not apply the old kit. */
    fun forgetKit(arena: Arena.Id)
}
