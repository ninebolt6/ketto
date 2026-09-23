package net.ninebolt.onevsone.domain

/**
 * An arena. A persistent entity holding static configuration (enabled, spawns).
 * In-progress match state lives in the separate ArenaMatch aggregate; the 1:1
 * pairing of the two is structurally guaranteed by ArenaRegistry. Kit contents
 * are held by infrastructure.
 * Immutable: changes produce a new instance via enable()/disable()/withSpawn()
 * and are replaced in the registry and persistence.
 */
data class Arena private constructor(
    val id: Id,
    val enabled: Boolean = false,
    val spawn1: WorldPosition? = null,
    val spawn2: WorldPosition? = null
) {
    /**
     * Arena identifier. Wraps the name that matches persistence, signs, and
     * file names. Instances can only be created through companion factories,
     * so no instance can violate the acceptance rules.
     */
    @JvmInline
    value class Id private constructor(val name: String) {
        override fun toString(): String = name

        companion object {
            /**
             * Arena-name acceptance rules. The name maps directly to a
             * persistence file name, so path-unsafe characters, surrounding
             * whitespace, and reserved names are rejected: "players" and the
             * command keyword "create" (which would collide with
             * `/1vs1 arena create`).
             */
            private fun isValidName(name: String): Boolean =
                name.isNotBlank() &&
                    name.length <= 64 &&
                    name.trim() == name &&
                    name.none { it == '/' || it == '\\' || it == '.' || it == ':' || it.isISOControl() } &&
                    !name.equals("players", ignoreCase = true) &&
                    !name.equals("create", ignoreCase = true)

            /** Conversion from external input such as command args or persisted data. Invalid names yield null. */
            fun of(name: String): Id? = if (isValidName(name)) Id(name) else null

            /** For names known to be valid. Invalid names throw IllegalArgumentException. */
            fun new(name: String): Id =
                of(name) ?: throw IllegalArgumentException("invalid arena name: '$name'")
        }
    }

    val name: String get() = id.name

    fun enable(): Arena = copy(enabled = true)

    fun disable(): Arena = copy(enabled = false)

    /** slot is 0-based, same as spawn(slot). */
    fun withSpawn(slot: Int, position: WorldPosition): Arena {
        require(slot in 0..1) { "spawn slot must be 0 or 1 (was $slot)" }
        return if (slot == 0) copy(spawn1 = position) else copy(spawn2 = position)
    }

    fun spawn(slot: Int): WorldPosition? = when (slot) {
        0 -> spawn1
        1 -> spawn2
        else -> null
    }

    companion object {
        fun new(
            id: Id,
            enabled: Boolean = false,
            spawn1: WorldPosition? = null,
            spawn2: WorldPosition? = null
        ): Arena = Arena(id, enabled, spawn1, spawn2)
    }
}
