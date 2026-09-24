package net.ninebolt.onevsone.domain

// Out of scope by design: third-party potion effects, unattributable interference (e.g. third-party lava), and igniting pre-existing TNT; wind-charge knockback can remain though its damage is blocked.
data class ParticipantRestrictions private constructor(
    val horizontalMoveFrozen: Boolean,
    val damageCancelled: Boolean,
    val opponentDamageOnly: Boolean,
    val teleportRestriction: TeleportRestriction,
    val blockBreakCancelled: Boolean,
    // Placing is cancelled because placed blocks could not be removed while breaking is denied.
    val blockPlaceCancelled: Boolean,
    // The start-of-match backup overwrites the inventory after the kit swap, so drops could otherwise carry items out.
    val itemDropCancelled: Boolean,
    val inventoryTransferCancelled: Boolean,
    val itemPickupCancelled: Boolean,
    val commandsBlocked: Boolean,
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
                commandsBlocked = true,
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
                commandsBlocked = true,
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
                commandsBlocked = true,
            )

            // No transition leaves participants in WAITING; commands are denied in every state except ONEMORE.
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
                commandsBlocked = false,
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
                commandsBlocked = true,
            )
        }
    }
}
