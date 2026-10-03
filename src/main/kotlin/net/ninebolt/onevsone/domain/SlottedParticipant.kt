package net.ninebolt.onevsone.domain

import kotlin.uuid.Uuid

data class SlottedParticipant(val participant: Participant, val slot: SpawnSlot) {
    val id: Uuid get() = participant.id
    val name: String get() = participant.name
}
