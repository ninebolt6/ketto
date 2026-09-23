package net.ninebolt.onevsone.domain

/** Rule deriving a participant's teleport permission from the state. */
enum class TeleportRestriction {
    UNRESTRICTED,

    /** Only ender pearls and the plugin's own teleports are allowed. */
    ENDER_PEARL_ONLY,

    /** Only the plugin's own teleports are allowed (movement frozen). */
    PLUGIN_ONLY;

    fun allows(trigger: TeleportTrigger): Boolean = when (this) {
        UNRESTRICTED -> true
        ENDER_PEARL_ONLY -> trigger == TeleportTrigger.INTERNAL || trigger == TeleportTrigger.ENDER_PEARL
        PLUGIN_ONLY -> trigger == TeleportTrigger.INTERNAL
    }
}
