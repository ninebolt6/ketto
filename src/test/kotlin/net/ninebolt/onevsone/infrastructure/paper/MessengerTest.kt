package net.ninebolt.onevsone.infrastructure.paper

import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.infrastructure.paper.fixtures.RecordingLogger
import org.bukkit.configuration.file.YamlConfiguration
import org.mockbukkit.mockbukkit.MockBukkit
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Locale
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Unit tests for language-bundle completeness, rendering, and locale resolution. */
class MessengerTest {

    @TempDir
    lateinit var folder: File

    private val logger = Logger.getLogger("messages-test")
    private val plain = PlainTextComponentSerializer.plainText()

    private fun load(language: String = "auto", logger: Logger = this.logger) =
        Messenger.load(File(folder, "messages"), "ja", language, logger)

    /** Extracts the set of leaf keys from a bundled resource. */
    private fun bundledKeys(lang: String): Set<String> {
        val config = javaClass.getResourceAsStream("/messages/$lang.yaml")!!
            .reader().use(YamlConfiguration::loadConfiguration)
        return config.getKeys(true).filterTo(HashSet()) { config.isString(it) }
    }

    @Test
    fun `bundled languages cover every message key`() {
        val keys = MessageKey.entries.mapTo(HashSet()) { it.name }
        assertEquals(keys, bundledKeys("ja"))
        assertEquals(keys, bundledKeys("en"))
    }

    @Test
    fun `every bundled template parses and differs from its key`() {
        val mini = MiniMessage.miniMessage()
        listOf("ja", "en").forEach { lang ->
            val config = javaClass.getResourceAsStream("/messages/$lang.yaml")!!
                .reader().use(YamlConfiguration::loadConfiguration)
            config.getKeys(true).filter { config.isString(it) }.forEach { key ->
                val template = config.getString(key)!!
                assertNotEquals(key, template, "$lang template equals key name: $key")
                mini.deserialize(template)
            }
        }
    }

    @Test
    fun `render substitutes unparsed placeholder literally`() {
        val messenger = load()
        // <name> is unparsed, so tag-like strings pass through literally
        val text = plain.serialize(messenger.render(Message.MatchJoined("<b>x</b>"), "ja"))
        assertTrue(text.contains("アリーナ: <b>x</b> に参加しました"))
    }

    @Test
    fun `nested arg renders in same locale`() {
        val messenger = load()
        assertEquals(
            "状態: Ingame",
            plain.serialize(messenger.render(Message.ArenaInfoState(ArenaState.INGAME), "ja"))
        )
        assertEquals(
            "State: Ingame",
            plain.serialize(messenger.render(Message.ArenaInfoState(ArenaState.INGAME), "en"))
        )
    }

    @Test
    fun `unknown lang falls back to default`() {
        val messenger = load()
        assertEquals(
            plain.serialize(messenger.render(Message.MatchGameStart, "ja")),
            plain.serialize(messenger.render(Message.MatchGameStart, "fr"))
        )
    }

    @Test
    fun `auto mode resolves player locale and falls back for console`() {
        val messenger = load()
        val server = MockBukkit.mock()
        try {
            val ja = server.addPlayer("Ja").also { it.setLocale(Locale.JAPAN) }
            val en = server.addPlayer("En").also { it.setLocale(Locale.ENGLISH) }
            val console = server.consoleSender

            messenger.send(ja, Message.MatchJoined("a1"))
            messenger.send(en, Message.MatchJoined("a1"))
            messenger.send(console, Message.MatchJoined("a1"))

            assertTrue(plain.serialize(ja.nextComponentMessage()!!).contains("アリーナ: a1 に参加しました"))
            assertTrue(plain.serialize(en.nextComponentMessage()!!).contains("Joined arena: a1"))
            assertTrue(plain.serialize(console.nextComponentMessage()!!).contains("アリーナ: a1 に参加しました"))
        } finally {
            MockBukkit.unmock()
        }
    }

    @Test
    fun `fixed language overrides player locale`() {
        val messenger = load(language = "en")
        val server = MockBukkit.mock()
        try {
            val ja = server.addPlayer("Ja").also { it.setLocale(Locale.JAPAN) }
            messenger.send(ja, Message.MatchJoined("a1"))
            assertTrue(plain.serialize(ja.nextComponentMessage()!!).contains("Joined arena: a1"))
        } finally {
            MockBukkit.unmock()
        }
    }

    @Test
    fun `dataFolder lang file overrides bundled value`() {
        File(folder, "messages").mkdirs()
        File(folder, "messages/ja.yaml").writeText("MATCH_JOINED: \"<green>OVERRIDDEN <name>\"\n")
        val messenger = load()
        val text = plain.serialize(messenger.render(Message.MatchJoined("a1"), "ja"))
        assertTrue(text.contains("OVERRIDDEN a1"))
    }

    @Test
    fun `extra language file registers and missing keys warn`() {
        File(folder, "messages").mkdirs()
        File(folder, "messages/de.yaml").writeText("MATCH_JOINED: \"<green>Beigetreten: <name>\"\n")
        val recording = RecordingLogger()
        val messenger = load(logger = recording)
        assertTrue(recording.warnings.any { "missing key" in it })
        assertEquals(
            "Beigetreten: a1",
            plain.serialize(messenger.render(Message.MatchJoined("a1"), "de"))
        )
    }

    @Test
    fun `yml extension is also accepted`() {
        File(folder, "messages").mkdirs()
        File(folder, "messages/de.yml").writeText("MATCH_JOINED: \"<green>Beigetreten: <name>\"\n")
        val messenger = load()
        assertEquals(
            "Beigetreten: a1",
            plain.serialize(messenger.render(Message.MatchJoined("a1"), "de"))
        )
    }

    @Test
    fun `yaml wins over yml for the same language`() {
        File(folder, "messages").mkdirs()
        File(folder, "messages/de.yml").writeText("MATCH_JOINED: \"<green>yml <name>\"\n")
        File(folder, "messages/de.yaml").writeText("MATCH_JOINED: \"<green>yaml <name>\"\n")
        val messenger = load()
        assertEquals(
            "yaml a1",
            plain.serialize(messenger.render(Message.MatchJoined("a1"), "de"))
        )
    }

    @Test
    fun `backfill appends only missing keys and keeps custom values`() {
        File(folder, "messages").mkdirs()
        val file = File(folder, "messages/ja.yaml")
        file.writeText("# user comment\nMATCH_JOINED: \"<green>CUSTOM <name>\"")
        val bundled = javaClass.getResourceAsStream("/messages/ja.yaml")!!
            .reader().use(YamlConfiguration::loadConfiguration)

        val added = LanguageFiles.backfill(file, bundled)
        assertEquals(bundledKeys("ja").size - 1, added)

        val reloaded = YamlConfiguration.loadConfiguration(file)
        assertEquals("<green>CUSTOM <name>", reloaded.getString("MATCH_JOINED"))
        bundledKeys("ja").forEach { assertTrue(reloaded.isString(it), "still missing: $it") }
        assertTrue(file.readText().startsWith("# user comment"))

        assertEquals(0, LanguageFiles.backfill(file, bundled))
    }

    @Test
    fun `rendered color matches template`() {
        val messenger = load()
        assertEquals(NamedTextColor.RED, messenger.render(Message.StatsNone).color())
        assertEquals(NamedTextColor.GREEN, messenger.render(Message.MatchGameStart).color())
    }
}
