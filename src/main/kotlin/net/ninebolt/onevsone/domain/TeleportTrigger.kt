package net.ninebolt.onevsone.domain

/** Teleport source classified without Bukkit. The cause->classification mapping lives in infrastructure. */
enum class TeleportTrigger {
    /** Teleport caused by the plugin itself (e.g. spawn move at match start). */
    INTERNAL,

    ENDER_PEARL,

    /** Any other external source: commands, portals, other plugins, etc. */
    EXTERNAL
}
