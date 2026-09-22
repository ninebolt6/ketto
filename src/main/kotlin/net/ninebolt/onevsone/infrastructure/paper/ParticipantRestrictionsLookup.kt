package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.application.ArenaApplicationService
import net.ninebolt.onevsone.domain.ParticipantRestrictions
import org.bukkit.entity.Player
import kotlin.uuid.toKotlinUuid

/** Derives restrictions from the participant's current match state. null for non-participants. */
internal fun ArenaApplicationService.restrictionsOf(player: Player): ParticipantRestrictions? =
    matchOf(player.uniqueId.toKotlinUuid())?.let { ParticipantRestrictions.forState(it.state) }
