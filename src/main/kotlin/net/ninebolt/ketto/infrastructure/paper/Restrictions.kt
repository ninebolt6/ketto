package net.ninebolt.ketto.infrastructure.paper

import net.ninebolt.ketto.application.MatchParticipationService
import net.ninebolt.ketto.domain.ParticipantRestrictions
import org.bukkit.entity.Player
import kotlin.uuid.toKotlinUuid

internal fun MatchParticipationService.findRestrictions(player: Player): ParticipantRestrictions? = findMatchOf(player.uniqueId.toKotlinUuid())?.let { ParticipantRestrictions.forState(it.state.kind) }
