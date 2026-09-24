package net.ninebolt.onevsone.domain

enum class TeleportRestriction {
    UNRESTRICTED,

    ENDER_PEARL_ONLY,

    PLUGIN_ONLY,

    ;

    fun allows(trigger: TeleportTrigger): Boolean = when (this) {
        UNRESTRICTED -> true
        ENDER_PEARL_ONLY -> trigger == TeleportTrigger.INTERNAL || trigger == TeleportTrigger.ENDER_PEARL
        PLUGIN_ONLY -> trigger == TeleportTrigger.INTERNAL
    }
}
