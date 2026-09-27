package net.ninebolt.onevsone.domain

sealed interface Arena {
    val id: Id
    val spawn1: WorldPosition?
    val spawn2: WorldPosition?

    val enabled: Boolean get() = this is Enabled
    val name: String get() = id.name

    fun spawn(slot: SpawnSlot): WorldPosition? = when (slot) {
        SpawnSlot.FIRST -> spawn1
        SpawnSlot.SECOND -> spawn2
    }

    fun withSpawn(slot: SpawnSlot, position: WorldPosition): Arena

    data class Disabled private constructor(
        override val id: Id,
        override val spawn1: WorldPosition? = null,
        override val spawn2: WorldPosition? = null,
    ) : Arena {
        override fun withSpawn(slot: SpawnSlot, position: WorldPosition): Disabled = when (slot) {
            SpawnSlot.FIRST -> copy(spawn1 = position)
            SpawnSlot.SECOND -> copy(spawn2 = position)
        }

        val missingSpawns: List<SpawnSlot> get() = SpawnSlot.entries.filter { spawn(it) == null }

        fun enable(): EnableOutcome {
            val first = spawn1
            val second = spawn2
            return if (first != null && second != null) {
                EnableOutcome.Ready(Enabled.restored(id, first, second))
            } else {
                EnableOutcome.MissingSpawns(missingSpawns)
            }
        }

        companion object {
            fun new(id: Id): Disabled = Disabled(id)

            fun restored(id: Id, spawn1: WorldPosition?, spawn2: WorldPosition?): Disabled = Disabled(id, spawn1, spawn2)
        }
    }

    data class Enabled private constructor(
        override val id: Id,
        override val spawn1: WorldPosition,
        override val spawn2: WorldPosition,
    ) : Arena {
        override fun spawn(slot: SpawnSlot): WorldPosition = when (slot) {
            SpawnSlot.FIRST -> spawn1
            SpawnSlot.SECOND -> spawn2
        }

        override fun withSpawn(slot: SpawnSlot, position: WorldPosition): Enabled = when (slot) {
            SpawnSlot.FIRST -> copy(spawn1 = position)
            SpawnSlot.SECOND -> copy(spawn2 = position)
        }

        fun disable(): Disabled = Disabled.restored(id, spawn1, spawn2)

        companion object {
            fun restored(id: Id, spawn1: WorldPosition, spawn2: WorldPosition): Enabled = Enabled(id, spawn1, spawn2)
        }
    }

    @JvmInline
    value class Id private constructor(val name: String) {
        override fun toString(): String = name

        companion object {
            // "create" collides with the arena command's create literal
            private fun isValidName(name: String): Boolean = name.isNotBlank() &&
                name.length <= 64 &&
                name.trim() == name &&
                name.none { it.isISOControl() } &&
                !name.equals("create", ignoreCase = true)

            fun of(name: String): Id? = if (isValidName(name)) Id(name) else null
        }
    }
}
