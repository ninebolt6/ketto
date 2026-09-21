package net.ninebolt.onevsone.infrastructure.paper

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.AfterEach
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock

class PaperInventorySnapshotTest {

    private lateinit var server: ServerMock

    @BeforeEach
    fun setup() {
        server = MockBukkit.mock()
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    @Test
    fun `capture clones items and preserves slots including nulls`() {
        val inv = server.addPlayer("Alice").inventory
        val sword = ItemStack.of(Material.DIAMOND_SWORD)
        val contents = arrayOfNulls<ItemStack>(41)
        contents[0] = sword
        contents[40] = ItemStack.of(Material.SHIELD)
        inv.contents = contents

        val snapshot = PaperInventorySnapshot.capture(inv)
        assertEquals(Material.DIAMOND_SWORD, snapshot.items[0]?.type)
        assertNotSame(sword, snapshot.items[0])
        assertNull(snapshot.items[1])
        assertEquals(Material.SHIELD, snapshot.items[40]?.type)
    }

    @Test
    fun `apply restores exact slots and armor authoritatively`() {
        val inv = server.addPlayer("Alice").inventory
        val helmet = ItemStack.of(Material.IRON_HELMET)
        val bread = ItemStack.of(Material.BREAD)
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
        val inv = server.addPlayer("Alice").inventory
        val bread = ItemStack.of(Material.BREAD)
        val snapshot = PaperInventorySnapshot(items = listOf(bread))
        snapshot.apply(inv)
        assertNotSame(snapshot.items[0], inv.contents[0])
        assertEquals(Material.BREAD, inv.contents[0]?.type)
    }

    @Test
    fun `legacy 36 length items snapshot applies to 41 slot inventory`() {
        val inv = server.addPlayer("Alice").inventory
        val items = List(36) { ItemStack.of(Material.BREAD) }
        PaperInventorySnapshot(items = items).apply(inv)
        assertEquals(Material.BREAD, inv.contents[35]?.type)
        assertNull(inv.contents[40])
    }

    @Test
    fun `apply always writes four armor slots`() {
        val inv = server.addPlayer("Alice").inventory
        val helmet = ItemStack.of(Material.IRON_HELMET)
        PaperInventorySnapshot(armor = List(6) { helmet }, items = emptyList()).apply(inv)
        assertEquals(4, inv.armorContents.size)
    }
}
