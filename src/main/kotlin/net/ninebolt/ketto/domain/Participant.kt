package net.ninebolt.ketto.domain

import kotlin.uuid.Uuid

data class Participant private constructor(val id: Uuid, val name: String) {
    companion object {
        fun new(name: String): Participant = new(Uuid.random(), name)

        fun new(id: Uuid, name: String): Participant {
            require(name.isNotBlank()) { "participant name must not be blank" }
            return Participant(id, name)
        }
    }
}
