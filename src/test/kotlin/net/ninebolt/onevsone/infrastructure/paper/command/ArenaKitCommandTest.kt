package net.ninebolt.onevsone.infrastructure.paper.command

import net.ninebolt.onevsone.domain.Arena
import net.ninebolt.onevsone.infrastructure.paper.fixtures.TestEnv
import net.ninebolt.onevsone.infrastructure.paper.fixtures.drainMessages
import net.ninebolt.onevsone.infrastructure.paper.fixtures.opPlayer
import net.ninebolt.onevsone.infrastructure.paper.fixtures.runCommand
import org.bukkit.Material
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
        env.runCommand(op, "arena", "arena1", "kit", "set")
        assertTrue(op.drainMessages().any { it.contains("のインベントリを設定しました") })
        assertEquals(Material.DIAMOND_SWORD, env.equipment.kitOf(Arena.Id.new("arena1"))?.items?.get(0)?.type)
    }
}
