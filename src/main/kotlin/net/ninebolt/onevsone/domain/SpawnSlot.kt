package net.ninebolt.onevsone.domain

/**
 * One of an arena's two spawn slots (spawn1/spawn2). The participant at join
 * order index i teleports to the slot with index i. index is the internal
 * 0-based order; number is the user-facing 1-based slot in commands/messages.
 */
enum class SpawnSlot(val index: Int) {
    FIRST(0),
    SECOND(1);

    /** User-facing 1-based slot number (commands/messages). */
    val number: Int get() = index + 1

    companion object {
        /** Internal 0-based index -> slot; null when out of range. */
        fun ofIndex(index: Int): SpawnSlot? = entries.getOrNull(index)

        /** User-facing 1-based number -> slot; null when out of range. */
        fun ofNumber(number: Int): SpawnSlot? = entries.getOrNull(number - 1)
    }
}
