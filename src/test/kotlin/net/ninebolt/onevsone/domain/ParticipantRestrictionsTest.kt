package net.ninebolt.onevsone.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ParticipantRestrictionsTest {

    @Test
    fun `restrictions matrix matches arena states`() {
        fun assertRestrictions(
            state: ArenaState.Kind,
            horizontalMoveFrozen: Boolean,
            damagePolicy: DamagePolicy,
            teleportRestriction: TeleportRestriction,
            blockBreakCancelled: Boolean,
            blockPlaceCancelled: Boolean,
            itemDropCancelled: Boolean,
            inventoryTransferCancelled: Boolean,
            itemPickupCancelled: Boolean,
            commandsBlocked: Boolean,
        ) {
            val r = ParticipantRestrictions.forState(state)
            assertEquals(horizontalMoveFrozen, r.horizontalMoveFrozen, "$state.horizontalMoveFrozen")
            assertEquals(damagePolicy, r.damagePolicy, "$state.damagePolicy")
            assertEquals(teleportRestriction, r.teleportRestriction, "$state.teleportRestriction")
            assertEquals(blockBreakCancelled, r.blockBreakCancelled, "$state.blockBreakCancelled")
            assertEquals(blockPlaceCancelled, r.blockPlaceCancelled, "$state.blockPlaceCancelled")
            assertEquals(itemDropCancelled, r.itemDropCancelled, "$state.itemDropCancelled")
            assertEquals(
                inventoryTransferCancelled,
                r.inventoryTransferCancelled,
                "$state.inventoryTransferCancelled",
            )
            assertEquals(itemPickupCancelled, r.itemPickupCancelled, "$state.itemPickupCancelled")
            assertEquals(commandsBlocked, r.commandsBlocked, "$state.commandsBlocked")
        }

        assertRestrictions(
            ArenaState.Kind.WAITING,
            horizontalMoveFrozen = false,
            damagePolicy = DamagePolicy.UNRESTRICTED,
            teleportRestriction = TeleportRestriction.UNRESTRICTED,
            blockBreakCancelled = false,
            blockPlaceCancelled = false,
            itemDropCancelled = false,
            inventoryTransferCancelled = false,
            itemPickupCancelled = false,
            commandsBlocked = true,
        )
        assertRestrictions(
            ArenaState.Kind.ONEMORE,
            horizontalMoveFrozen = false,
            damagePolicy = DamagePolicy.UNRESTRICTED,
            teleportRestriction = TeleportRestriction.UNRESTRICTED,
            blockBreakCancelled = false,
            blockPlaceCancelled = false,
            itemDropCancelled = false,
            inventoryTransferCancelled = false,
            itemPickupCancelled = false,
            commandsBlocked = false,
        )
        assertRestrictions(
            ArenaState.Kind.COUNTDOWN,
            horizontalMoveFrozen = false,
            damagePolicy = DamagePolicy.UNRESTRICTED,
            teleportRestriction = TeleportRestriction.ENDER_PEARL_ONLY,
            blockBreakCancelled = false,
            blockPlaceCancelled = false,
            itemDropCancelled = false,
            inventoryTransferCancelled = false,
            itemPickupCancelled = false,
            commandsBlocked = true,
        )
        assertRestrictions(
            ArenaState.Kind.ROUNDCOUNTDOWN,
            horizontalMoveFrozen = true,
            damagePolicy = DamagePolicy.BLOCKED,
            teleportRestriction = TeleportRestriction.PLUGIN_ONLY,
            blockBreakCancelled = true,
            blockPlaceCancelled = true,
            itemDropCancelled = true,
            inventoryTransferCancelled = true,
            itemPickupCancelled = true,
            commandsBlocked = true,
        )
        assertRestrictions(
            ArenaState.Kind.INGAME,
            horizontalMoveFrozen = false,
            damagePolicy = DamagePolicy.OPPONENT_ONLY,
            teleportRestriction = TeleportRestriction.ENDER_PEARL_ONLY,
            blockBreakCancelled = true,
            blockPlaceCancelled = true,
            itemDropCancelled = true,
            inventoryTransferCancelled = true,
            itemPickupCancelled = true,
            commandsBlocked = true,
        )
    }
}
