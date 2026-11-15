package net.ninebolt.ketto.e2e

import net.ninebolt.ketto.e2e.fixtures.PaperServer
import net.ninebolt.ketto.e2e.fixtures.PaperServerExtension
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.test.assertTrue

@ExtendWith(PaperServerExtension::class)
class PluginLoadE2ETest {
    @Test
    fun `plugin enables and accepts console commands`(server: PaperServer) {
        server.configurePlugin("language: en\ndefault-language: en\nrequired-wins: 1\n")
        server.start()

        val plugins = server.command("plugins")
        assertTrue(Regex("(?:§a|<green>)ketto", RegexOption.IGNORE_CASE).containsMatchIn(plugins), plugins)
        val database = server.pluginDirectory.resolve("data.db")
        assertTrue(database.isRegularFile())
        assertTrue(database.fileSize() > 0)
        val created = server.command("ketto arena create smoke")
        assertTrue(created.contains("Created arena: smoke"), created)
    }
}
