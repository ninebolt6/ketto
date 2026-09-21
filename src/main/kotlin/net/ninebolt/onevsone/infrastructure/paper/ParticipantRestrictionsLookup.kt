package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.application.ArenaApplicationService
import net.ninebolt.onevsone.domain.ParticipantRestrictions
import org.bukkit.entity.Player
import kotlin.uuid.toKotlinUuid

/** 参加者の現在のマッチ状態から制約を導出する。非参加者は null。 */
internal fun ArenaApplicationService.restrictionsOf(player: Player): ParticipantRestrictions? =
    matchOf(player.uniqueId.toKotlinUuid())?.let { ParticipantRestrictions.forState(it.state) }
