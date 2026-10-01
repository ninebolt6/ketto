package net.ninebolt.onevsone.infrastructure.paper.message

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.infrastructure.paper.fixtures.RecordingLogger
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockbukkit.mockbukkit.MockBukkit
import java.io.File
import java.util.Locale
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MessengerTest {

    @TempDir
    lateinit var folder: File

    private val logger = Logger.getLogger("messages-test")
    private val plain = PlainTextComponentSerializer.plainText()

    private fun load(fallbackLang: String = "en", language: String = "auto", logger: Logger = this.logger) = Messenger.load(File(folder, "messages"), fallbackLang, language, logger)

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
    fun `restore pending message explains the join restriction in both languages`() {
        val messenger = load()

        assertEquals(
            "前回のインベントリ復元が完了していないため、アリーナに参加できません。解消しない場合は管理者に連絡してください。",
            plain.serialize(messenger.render(Message.MatchRestorePending, "ja")),
        )
        assertEquals(
            "You cannot join until your previous inventory has been restored. Contact an administrator if this continues.",
            plain.serialize(messenger.render(Message.MatchRestorePending, "en")),
        )
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
        val text = plain.serialize(messenger.render(Message.MatchJoined("<b>x</b>"), "ja"))
        assertTrue(text.contains("アリーナ: <b>x</b> に参加しました"))
    }

    @Test
    fun `nested arg renders in same locale`() {
        val messenger = load()
        assertEquals(
            "状態: Ingame",
            plain.serialize(messenger.render(Message.ArenaInfoState(ArenaState.Kind.INGAME), "ja")),
        )
        assertEquals(
            "State: Ingame",
            plain.serialize(messenger.render(Message.ArenaInfoState(ArenaState.Kind.INGAME), "en")),
        )
    }

    @Test
    fun `unknown lang falls back to default`() {
        val messenger = load()
        assertEquals(
            plain.serialize(messenger.render(Message.MatchGameStart, "en")),
            plain.serialize(messenger.render(Message.MatchGameStart, "fr")),
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
            assertTrue(plain.serialize(console.nextComponentMessage()!!).contains("Joined arena: a1"))
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
            plain.serialize(messenger.render(Message.MatchJoined("a1"), "de")),
        )
    }

    @Test
    fun `key missing from a partial language file falls back to the fallback bundle`() {
        File(folder, "messages").mkdirs()
        File(folder, "messages/de.yaml").writeText("MATCH_JOINED: \"<green>Beigetreten: <name>\"\n")
        val messenger = load()
        assertEquals(
            plain.serialize(messenger.render(Message.MatchGameStart, "en")),
            plain.serialize(messenger.render(Message.MatchGameStart, "de")),
        )
    }

    @Test
    fun `yml extension is also accepted`() {
        File(folder, "messages").mkdirs()
        File(folder, "messages/de.yml").writeText("MATCH_JOINED: \"<green>Beigetreten: <name>\"\n")
        val messenger = load()
        assertEquals(
            "Beigetreten: a1",
            plain.serialize(messenger.render(Message.MatchJoined("a1"), "de")),
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
            plain.serialize(messenger.render(Message.MatchJoined("a1"), "de")),
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
    fun `unbundled fallback language renders the key name and warns once`() {
        val recording = RecordingLogger()
        val messenger = Messenger.load(File(folder, "messages"), "fr", "auto", recording)
        assertEquals("MATCH_GAME_START", plain.serialize(messenger.render(Message.MatchGameStart, "fr")))
        messenger.render(Message.MatchGameStart, "fr")
        assertEquals(1, recording.warnings.count { it.contains("Missing message key") })
    }

    @Test
    fun `player with unsupported locale falls back to default language`() {
        val messenger = load()
        val server = MockBukkit.mock()
        try {
            val fr = server.addPlayer("Fr").also { it.setLocale(Locale.FRENCH) }
            messenger.send(fr, Message.MatchJoined("a1"))
            assertTrue(plain.serialize(fr.nextComponentMessage()!!).contains("Joined arena: a1"))
        } finally {
            MockBukkit.unmock()
        }
    }

    @Test
    fun `syncBundled writes the bundled language files into the data folder`() {
        val recording = RecordingLogger()
        LanguageFiles.syncBundled(folder, recording) { path ->
            File(folder, path).apply { parentFile?.mkdirs() }
                .writeText(javaClass.getResourceAsStream("/$path")!!.reader().readText())
        }
        LanguageFiles.BUNDLED_LANGS.forEach { lang ->
            bundledKeys(lang).forEach { key ->
                assertTrue(
                    YamlConfiguration.loadConfiguration(File(folder, "messages/$lang.yaml")).isString(key),
                    "missing key in messages/$lang.yaml: $key",
                )
            }
        }
        assertEquals(0, recording.warnings.size)
    }

    @Test
    fun `syncBundled reports keys appended to an existing file`() {
        val recording = RecordingLogger()
        File(folder, "messages").mkdirs()
        File(folder, "messages/ja.yaml").writeText("MATCH_JOINED: \"<green>CUSTOM <name>\"")
        LanguageFiles.syncBundled(folder, recording) { path ->
            val file = File(folder, path)
            file.parentFile?.mkdirs()
            if (!file.exists()) file.writeText(javaClass.getResourceAsStream("/$path")!!.reader().readText())
        }
        assertTrue(recording.infos.any { it.contains("messages/ja.yaml") })
        assertEquals("<green>CUSTOM <name>", YamlConfiguration.loadConfiguration(File(folder, "messages/ja.yaml")).getString("MATCH_JOINED"))
    }

    @Test
    fun `syncBundled does not save resources over files that already exist`() {
        File(folder, "messages").mkdirs()
        File(folder, "messages/ja.yaml").writeText("MATCH_JOINED: \"x\"")
        val saved = mutableListOf<String>()

        LanguageFiles.syncBundled(folder, RecordingLogger()) { saved.add(it) }

        assertEquals(listOf("messages/en.yaml"), saved)
    }

    @Test
    fun `a player whose locale lookup fails falls back to the default language`() {
        val messenger = load()
        val player = mockk<Player>()
        every { player.locale() } throws RuntimeException("locale unavailable")
        every { player.sendMessage(any<Component>()) } just runs

        messenger.send(player, Message.MatchJoined("a1"))

        verify { player.sendMessage(any<Component>()) }
    }

    @Test
    fun `backfill skips bundled section keys but keeps their string leaves`() {
        val file = File(folder, "en.yaml").also { it.writeText("MATCH_JOINED: \"x\"\n") }
        val bundled = YamlConfiguration()
        bundled.set("meta.author", "test")
        bundled.set("NEW_KEY", "fresh")

        val added = LanguageFiles.backfill(file, bundled)

        assertEquals(2, added)
        val merged = YamlConfiguration.loadConfiguration(file)
        assertTrue(merged.isString("meta.author"))
        assertTrue(merged.isString("NEW_KEY"))
    }

    @Test
    fun `loadBundles without a messages directory returns only the bundled languages`() {
        val bundles = LanguageFiles.loadBundles(File(folder, "absent"), "en", logger)
        assertEquals(setOf("en", "ja"), bundles.keys)
        assertTrue(bundles["en"]!!.containsKey(MessageKey.MATCH_JOINED.name))
    }

    @Test
    fun `override entries without a string value are skipped`() {
        File(folder, "messages").mkdirs()
        File(folder, "messages/de.yaml").writeText("MATCH_JOINED: \"<green>Beigetreten: <name>\"\nEMPTY_KEY:\nsection:\n  child: \"v\"\n")
        val bundles = LanguageFiles.loadBundles(File(folder, "messages"), "en", logger)
        assertEquals("<green>Beigetreten: <name>", bundles["de"]?.get("MATCH_JOINED"))
        assertFalse("EMPTY_KEY" in bundles["de"]!!)
        assertFalse("section" in bundles["de"]!!)
        assertEquals("v", bundles["de"]?.get("section.child"))
    }

    @Test
    fun `rendered color matches template`() {
        val messenger = load()
        assertEquals(NamedTextColor.RED, messenger.render(Message.StatsNone).color())
        assertEquals(NamedTextColor.GREEN, messenger.render(Message.MatchGameStart).color())
    }
}
