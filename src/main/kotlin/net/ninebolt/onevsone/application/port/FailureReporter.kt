package net.ninebolt.onevsone.application.port

/**
 * 保存・復元等の障害通知。ログ出力の実装は外側(infrastructure)が持つ。
 * 通常のユーザー拒否はここではなくユースケース結果で扱う。
 */
interface FailureReporter {
    fun warn(message: String)
    fun report(context: String, error: Throwable)
}

/** PersistenceFailure を warn に潰す共通の失敗経路。 */
internal inline fun FailureReporter.warnOnFailure(message: String, block: () -> Unit) {
    try {
        block()
    } catch (e: PersistenceFailure) {
        warn(message)
    }
}
