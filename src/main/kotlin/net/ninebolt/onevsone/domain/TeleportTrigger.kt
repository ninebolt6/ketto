package net.ninebolt.onevsone.domain

/** テレポートの発生源を Bukkit 非依存に分類したもの。原因→分類の写像はインフラ側。 */
enum class TeleportTrigger {
    /** プラグイン自身による移送(開始時スポーン移動など)。 */
    INTERNAL,

    /** エンダーパールによる移動。 */
    ENDER_PEARL,

    /** コマンド・ポータル・他プラグインなど、それ以外の外部発。 */
    EXTERNAL
}
