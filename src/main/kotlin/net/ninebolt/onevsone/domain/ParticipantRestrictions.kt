package net.ninebolt.onevsone.domain

/**
 * Pure rules deriving participant restrictions from the arena state.
 * Listeners only convert events; per-state decisions are centralized here.
 */
data class ParticipantRestrictions private constructor(
    val horizontalMoveFrozen: Boolean,
    val damageCancelled: Boolean,
    /** Limits entity-caused damage to the same-match opponent or self */
    val opponentDamageOnly: Boolean,
    /** Whether teleports are allowed. Blocks escape routes other than ender pearls */
    val teleportRestriction: TeleportRestriction,
    val blockBreakCancelled: Boolean,
    /** Placed blocks cannot be removed while breaking is denied; prevents arena pollution and camping */
    val blockPlaceCancelled: Boolean,
    /** After the kit swap the inventory is overwritten by the start-of-match backup; prevents carrying items out */
    val itemDropCancelled: Boolean,
    /** Blocks inventory<->world transfers: containers, item frames, armor stands, trading, etc. */
    val inventoryTransferCancelled: Boolean,
    /** Blocks direct acquisition: pickups, harvests, arrow retrieval, dispenser equipment, etc. */
    val itemPickupCancelled: Boolean,
    /** Commands are allowed only while waiting in ONEMORE */
    val commandsBlocked: Boolean
) {
    companion object {
        fun forState(state: ArenaState): ParticipantRestrictions = when (state) {
            ArenaState.ROUNDCOUNTDOWN -> ParticipantRestrictions(
                horizontalMoveFrozen = true,
                damageCancelled = true,
                opponentDamageOnly = false,
                teleportRestriction = TeleportRestriction.PLUGIN_ONLY,
                blockBreakCancelled = true,
                blockPlaceCancelled = true,
                itemDropCancelled = true,
                inventoryTransferCancelled = true,
                itemPickupCancelled = true,
                commandsBlocked = true
            )
            ArenaState.INGAME -> ParticipantRestrictions(
                horizontalMoveFrozen = false,
                damageCancelled = false,
                opponentDamageOnly = true,
                teleportRestriction = TeleportRestriction.ENDER_PEARL_ONLY,
                blockBreakCancelled = true,
                blockPlaceCancelled = true,
                itemDropCancelled = true,
                inventoryTransferCancelled = true,
                itemPickupCancelled = true,
                commandsBlocked = true
            )
            ArenaState.COUNTDOWN -> ParticipantRestrictions(
                horizontalMoveFrozen = false,
                damageCancelled = false,
                opponentDamageOnly = false,
                teleportRestriction = TeleportRestriction.ENDER_PEARL_ONLY,
                blockBreakCancelled = false,
                blockPlaceCancelled = false,
                itemDropCancelled = false,
                inventoryTransferCancelled = false,
                itemPickupCancelled = false,
                commandsBlocked = true
            )
            // WAITING / ONEMORE: unrestricted. No path leaves participants in WAITING,
            // but commands are denied everywhere except ONEMORE, so only ONEMORE allows them.
            ArenaState.ONEMORE -> ParticipantRestrictions(
                horizontalMoveFrozen = false,
                damageCancelled = false,
                opponentDamageOnly = false,
                teleportRestriction = TeleportRestriction.UNRESTRICTED,
                blockBreakCancelled = false,
                blockPlaceCancelled = false,
                itemDropCancelled = false,
                inventoryTransferCancelled = false,
                itemPickupCancelled = false,
                commandsBlocked = false
            )
            ArenaState.WAITING -> ParticipantRestrictions(
                horizontalMoveFrozen = false,
                damageCancelled = false,
                opponentDamageOnly = false,
                teleportRestriction = TeleportRestriction.UNRESTRICTED,
                blockBreakCancelled = false,
                blockPlaceCancelled = false,
                itemDropCancelled = false,
                inventoryTransferCancelled = false,
                itemPickupCancelled = false,
                commandsBlocked = true
            )
        }
    }
}
