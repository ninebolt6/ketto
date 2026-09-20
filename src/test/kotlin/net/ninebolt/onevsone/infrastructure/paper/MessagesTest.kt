package net.ninebolt.onevsone.infrastructure.paper

import net.kyori.adventure.text.format.NamedTextColor
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

/** 言語バンドルの網羅性・描画・ロケール解決の単体テスト。 */
class MessagesTest {

    @TempDir
    lateinit var folder: File

    private val logger = Logger.getLogger("messages-test")
    private val plain = PlainTextComponentSerializer.plainText()

    private fun load(language: String = "auto", logger: Logger = this.logger) =
        Messages.load(File(folder, "lang"), "ja", language, logger)

    /** 同梱リソースの葉キー集合を取り出す。 */
    private fun bundledKeys(lang: String): Set<String> {
        val config = javaClass.getResourceAsStream("/lang/messages_$lang.yml")!!
            .reader().use(YamlConfiguration::loadConfiguration)
        return config.getKeys(true).filterTo(HashSet()) { config.isString(it) }
    }

    @Test
    fun `bundled languages have identical key sets`() {
        assertEquals(bundledKeys("ja"), bundledKeys("en"))
    }

    @Test
    fun `every bundled ja key resolves to a template`() {
        val messages = load()
        bundledKeys("ja").forEach { key ->
            assertNotEquals(key, plain.serialize(messages.render(Msg(key), "ja")), "ja missing key: $key")
        }
    }

    @Test
    fun `render substitutes unparsed placeholder literally`() {
        val messages = load()
        // <name> は unparsed なのでタグ風文字列もそのまま出る
        val text = plain.serialize(messages.render(messages.joined("<b>x</b>"), "ja"))
        assertTrue(text.contains("アリーナ: <b>x</b> に参加しました"))
    }

    @Test
    fun `nested arg renders in same locale`() {
        val messages = load()
        assertEquals(
            "状態: Ingame",
            plain.serialize(messages.render(messages.arenaState(ArenaState.INGAME), "ja"))
        )
        assertEquals(
            "State: Ingame",
            plain.serialize(messages.render(messages.arenaState(ArenaState.INGAME), "en"))
        )
    }

    @Test
    fun `unknown lang falls back to default`() {
        val messages = load()
        assertEquals(
            plain.serialize(messages.render(messages.gameStart, "ja")),
            plain.serialize(messages.render(messages.gameStart, "fr"))
        )
    }

    @Test
    fun `auto mode resolves player locale and falls back for console`() {
        val messages = load()
        val server = MockBukkit.mock()
        try {
            val ja = server.addPlayer("Ja").also { it.setLocale(Locale.JAPAN) }
            val en = server.addPlayer("En").also { it.setLocale(Locale.ENGLISH) }
            val console = server.consoleSender

            messages.send(ja, messages.joined("a1"))
            messages.send(en, messages.joined("a1"))
            messages.send(console, messages.joined("a1"))

            assertTrue(plain.serialize(ja.nextComponentMessage()!!).contains("アリーナ: a1 に参加しました"))
            assertTrue(plain.serialize(en.nextComponentMessage()!!).contains("Joined arena: a1"))
            assertTrue(plain.serialize(console.nextComponentMessage()!!).contains("アリーナ: a1 に参加しました"))
        } finally {
            MockBukkit.unmock()
        }
    }

    @Test
    fun `fixed language overrides player locale`() {
        val messages = load(language = "en")
        val server = MockBukkit.mock()
        try {
            val ja = server.addPlayer("Ja").also { it.setLocale(Locale.JAPAN) }
            messages.send(ja, messages.joined("a1"))
            assertTrue(plain.serialize(ja.nextComponentMessage()!!).contains("Joined arena: a1"))
        } finally {
            MockBukkit.unmock()
        }
    }

    @Test
    fun `dataFolder lang file overrides bundled value`() {
        File(folder, "lang").mkdirs()
        File(folder, "lang/messages_ja.yml").writeText(
            """
            match:
              joined: "<green>OVERRIDDEN <name>"
            """.trimIndent()
        )
        val text = plain.serialize(load().render(load().joined("a1"), "ja"))
        assertTrue(text.contains("OVERRIDDEN a1"))
    }

    @Test
    fun `extra language file registers and missing keys warn`() {
        File(folder, "lang").mkdirs()
        File(folder, "lang/messages_de.yml").writeText("match:\n  joined: \"<green>Beigetreten: <name>\"\n")
        val recording = RecordingLogger()
        val messages = load(logger = recording)
        assertTrue(recording.warnings.any { "missing key" in it })
        assertEquals(
            "Beigetreten: a1",
            plain.serialize(messages.render(messages.joined("a1"), "de"))
        )
    }

    @Test
    fun `rendered color matches template`() {
        val messages = load()
        assertEquals(NamedTextColor.RED, messages.render(messages.noStats).color())
        assertEquals(NamedTextColor.GREEN, messages.render(messages.gameStart).color())
    }
}
