package net.ninebolt.onevsone.domain

data class Arena private constructor(
    val id: Id,
    val enabled: Boolean = false,
    val spawn1: WorldPosition? = null,
    val spawn2: WorldPosition? = null,
) {
    @JvmInline
    value class Id private constructor(val name: String) {
        override fun toString(): String = name

        companion object {
            // The name is used as a persistence file name; "players" and the "create" command keyword are reserved.
            private fun isValidName(name: String): Boolean = name.isNotBlank() &&
                name.length <= 64 &&
                name.trim() == name &&
                name.none { it == '/' || it == '\\' || it == '.' || it == ':' || it.isISOControl() } &&
                !name.equals("players", ignoreCase = true) &&
                !name.equals("create", ignoreCase = true)

            fun of(name: String): Id? = if (isValidName(name)) Id(name) else null

            fun new(name: String): Id = of(name) ?: throw IllegalArgumentException("invalid arena name: '$name'")
        }
    }

    val name: String get() = id.name

    fun enable(): Arena = copy(enabled = true)

    fun disable(): Arena = copy(enabled = false)

    fun withSpawn(slot: SpawnSlot, position: WorldPosition): Arena = when (slot) {
        SpawnSlot.FIRST -> copy(spawn1 = position)
        SpawnSlot.SECOND -> copy(spawn2 = position)
    }

    fun spawn(slot: SpawnSlot): WorldPosition? = when (slot) {
        SpawnSlot.FIRST -> spawn1
        SpawnSlot.SECOND -> spawn2
    }

    companion object {
        fun new(
            id: Id,
            enabled: Boolean = false,
            spawn1: WorldPosition? = null,
            spawn2: WorldPosition? = null,
        ): Arena = Arena(id, enabled, spawn1, spawn2)
    }
}
