package net.ninebolt.onevsone.application.port

/** 実行中タスクのキャンセルハンドル。repeat のコールバックにも同じものが渡される。 */
fun interface Cancellation {
    fun cancel()
}

/**
 * ゲームタイミング用スケジューラ。単位は tick。
 * domain は tick を知らず、application がタイミングを明示する。
 * BukkitTask はアダプター内に限定する。
 */
interface SchedulerPort {
    /** delayTicks 後(0 = 次 tick)に一度だけ実行。 */
    fun schedule(delayTicks: Long, action: () -> Unit): Cancellation

    /** initialDelayTicks 後に開始し periodTicks 間隔で繰り返す。 */
    fun repeat(initialDelayTicks: Long, periodTicks: Long, action: (Cancellation) -> Unit): Cancellation
}
