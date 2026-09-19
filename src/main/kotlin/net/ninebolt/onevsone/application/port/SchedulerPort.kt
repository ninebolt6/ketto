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
    /** 一度だけ実行。delayTicks=0 は次 tick。 */
    fun schedule(delayTicks: Long, action: () -> Unit): Cancellation

    fun repeat(initialDelayTicks: Long, periodTicks: Long, action: (Cancellation) -> Unit): Cancellation
}
