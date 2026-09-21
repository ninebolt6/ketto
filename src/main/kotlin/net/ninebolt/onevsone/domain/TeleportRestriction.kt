package net.ninebolt.onevsone.domain

/** 参加者のテレポート可否を状態から導出する規則。 */
enum class TeleportRestriction {
    /** 制約なし。 */
    UNRESTRICTED,

    /** エンダーパールとプラグイン自身の移送のみ許可する。 */
    ENDER_PEARL_ONLY,

    /** プラグイン自身の移送のみ許可する(移動凍結中)。 */
    PLUGIN_ONLY;

    fun allows(trigger: TeleportTrigger): Boolean = when (this) {
        UNRESTRICTED -> true
        ENDER_PEARL_ONLY -> trigger == TeleportTrigger.INTERNAL || trigger == TeleportTrigger.ENDER_PEARL
        PLUGIN_ONLY -> trigger == TeleportTrigger.INTERNAL
    }
}
