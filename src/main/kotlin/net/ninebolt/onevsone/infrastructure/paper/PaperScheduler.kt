package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.application.port.Cancellation
import net.ninebolt.onevsone.application.port.SchedulerPort
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.util.logging.Level

// BukkitTask is confined to this class
class PaperScheduler(private val plugin: JavaPlugin) : SchedulerPort {

    override fun schedule(delayTicks: Long, action: () -> Unit): Cancellation {
        val task = Bukkit.getScheduler().runTaskLater(
            plugin,
            Runnable {
                try {
                    action()
                } catch (e: Exception) {
                    plugin.logger.log(Level.SEVERE, "Scheduled task failed", e)
                }
            },
            delayTicks,
        )
        return Cancellation { task.cancel() }
    }

    override fun repeat(initialDelayTicks: Long, periodTicks: Long, action: (Cancellation) -> Unit): Cancellation {
        var task: BukkitTask? = null
        val cancellation = Cancellation { task?.cancel() }
        task = Bukkit.getScheduler().runTaskTimer(
            plugin,
            Runnable {
                try {
                    action(cancellation)
                } catch (e: Exception) {
                    plugin.logger.log(Level.SEVERE, "Repeating task failed; cancelling", e)
                    cancellation.cancel()
                }
            },
            initialDelayTicks,
            periodTicks,
        )
        return cancellation
    }
}
