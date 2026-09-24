package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.infrastructure.paper.fixtures.TestEnv
import net.ninebolt.onevsone.infrastructure.paper.fixtures.drainMessages
import net.ninebolt.onevsone.infrastructure.paper.fixtures.opPlayer
import net.ninebolt.onevsone.infrastructure.paper.fixtures.run
import org.bukkit.Material
import org.junit.jupiter.api.AfterEach
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ArenaKitCommandTest {

    @TempDir
    lateinit var folder: File

    private lateinit var env: TestEnv

    @BeforeEach
    fun setup() {
        env = TestEnv(folder)
    }

    @AfterEach
    fun tearDown() {
        env.close()
    }

    @Test
    fun `arena kit set saves kit`() {
        val op = env.opPlayer("Op")
        env.newArena()
        op.inventory.setItem(0, env.item(Material.DIAMOND_SWORD))
        env.run(op, "arena", "arena1", "kit", "set")
        assertTrue(op.drainMessages().any { it.contains("のインベントリを設定しました") })
        assertEquals(Material.DIAMOND_SWORD, env.equipment.kitOf(Arena.Id.new("arena1"))?.items?.get(0)?.type)
    }

    @Test
    fun `kit set rejects extra args`() {
        val op = env.opPlayer("Op")
        env.newArena()
        env.run(op, "arena", "arena1", "kit", "set", "extra")
        assertTrue(op.drainMessages().any { it.contains("/1vs1 arena <arena> kit set") })
    }

    @Test
    fun `console cannot set kit`() {
        val console = env.server.consoleSender
        env.newArena()
        env.run(console, "arena", "arena1", "kit", "set")
        assertTrue(console.drainMessages().any { it.contains("このコマンドはプレイヤーのみ実行可能です") })
    }
}
