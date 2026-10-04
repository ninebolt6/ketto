package net.ninebolt.ketto.application.port

import net.ninebolt.ketto.domain.Arena
import kotlin.uuid.Uuid

interface KitPort {
    fun applyKit(arena: Arena.Id, playerId: Uuid)

    fun saveKit(arena: Arena.Id, playerId: Uuid)

    // call when an arena is removed, or a recreated arena inherits its old kit
    fun forgetKit(arena: Arena.Id)

    fun stripKit(playerId: Uuid)
}
