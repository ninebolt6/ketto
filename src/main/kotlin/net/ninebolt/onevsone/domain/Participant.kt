package net.ninebolt.onevsone.domain

import kotlin.uuid.Uuid

/** Holds neither a Bukkit Player nor an inventory — only an identifier and a display name. */
data class Participant private constructor(val id: Uuid, val name: String) {
    companion object {
        /** A new participant. The identifier is generated internally. */
        fun new(name: String): Participant = new(Uuid.random(), name)

        /** For when the existing player's identifier is known. */
        fun new(id: Uuid, name: String): Participant {
            require(name.isNotBlank()) { "participant name must not be blank" }
            return Participant(id, name)
        }
    }
}
