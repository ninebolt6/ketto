package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.infrastructure.paper.fixtures.TestEnv
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PaperInventorySnapshotTest {

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
    fun `capture clones items and preserves slots including nulls`() {
        val inv = env.player("Alice").inventory
        val sword = env.item(Material.DIAMOND_SWORD)
        val contents = arrayOfNulls<ItemStack>(41)
        contents[0] = sword
        contents[40] = env.item(Material.SHIELD)
        inv.contents = contents

        val snapshot = PaperInventorySnapshot.capture(inv)
        assertEquals(Material.DIAMOND_SWORD, snapshot.items[0]?.type)
        assertNotSame(sword, snapshot.items[0])
        assertNull(snapshot.items[1])
        assertEquals(Material.SHIELD, snapshot.items[40]?.type)
    }

    @Test
    fun `apply restores exact slots and armor authoritatively`() {
        val inv = env.player("Alice").inventory
        val helmet = env.item(Material.IRON_HELMET)
        val bread = env.item(Material.BREAD)
        PaperInventorySnapshot(
            armor = listOf(helmet, null, null, null),
            items = listOf(bread)
        ).apply(inv)
        assertEquals(Material.BREAD, inv.contents[0]?.type)
        assertNull(inv.contents[1])
        assertEquals(Material.IRON_HELMET, inv.armorContents[0]?.type)
        assertNull(inv.armorContents[1])
    }

    @Test
    fun `apply clones so inventory copy is independent of snapshot`() {
        val inv = env.player("Alice").inventory
        val bread = env.item(Material.BREAD)
        val snapshot = PaperInventorySnapshot(items = listOf(bread))
        snapshot.apply(inv)
        assertNotSame(snapshot.items[0], inv.contents[0])
        assertEquals(Material.BREAD, inv.contents[0]?.type)
    }

    @Test
    fun `legacy 36 length items snapshot applies to 41 slot inventory`() {
        val inv = env.player("Alice").inventory
        val items = List(36) { env.item(Material.BREAD) }
        PaperInventorySnapshot(items = items).apply(inv)
        assertEquals(Material.BREAD, inv.contents[35]?.type)
        assertNull(inv.contents[40])
    }

    @Test
    fun `apply always writes four armor slots`() {
        val inv = env.player("Alice").inventory
        val helmet = env.item(Material.IRON_HELMET)
        PaperInventorySnapshot(armor = List(6) { helmet }, items = emptyList()).apply(inv)
        assertEquals(4, inv.armorContents.size)
    }
}
