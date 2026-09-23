package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.infrastructure.paper.fixtures.RecordingLogger
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PluginSettingsTest {

    private val logger = RecordingLogger()

    private fun load(yaml: String = ""): PluginSettings =
        PluginSettings.load(YamlConfiguration().apply { loadFromString(yaml) }, logger)

    @Test
    fun `defaults apply when keys are absent`() {
        val settings = load()
        assertEquals(3, settings.requiredWins)
        assertEquals("en", settings.defaultLanguage)
        assertEquals("auto", settings.language)
    }

    @Test
    fun `configured values are read`() {
        val settings = load("language: de\ndefault-language: ja\nrequired-wins: 5\n")
        assertEquals(5, settings.requiredWins)
        assertEquals("ja", settings.defaultLanguage)
        assertEquals("de", settings.language)
    }

    @Test
    fun `required-wins below 1 is coerced with a warning`() {
        val settings = load("required-wins: 0\n")
        assertEquals(1, settings.requiredWins)
        assertTrue(logger.warnings.any { "required-wins" in it })
    }
}
