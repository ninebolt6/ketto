package net.ninebolt.onevsone.infrastructure.persistence

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InventoryPayloadCodecTest {

    @Test
    fun `a payload missing the armor key decodes with empty armor`() {
        val yaml = YamlConfiguration()
        yaml.set("item", listOf<ItemStack>())
        val snapshot = InventoryPayloadCodec.decode(yaml.saveToString())
        assertTrue(snapshot.items.isEmpty())
        assertTrue(snapshot.armor.isEmpty())
    }

    @Test
    fun `codec decodes a payload without item lists as an empty snapshot`() {
        val snapshot = InventoryPayloadCodec.decode("prefix: value\n")
        assertTrue(snapshot.items.isEmpty())
        assertTrue(snapshot.armor.isEmpty())
    }

    @Test
    fun `codec tolerates non item entries in the payload lists`() {
        val snapshot = InventoryPayloadCodec.decode("item:\n- 'not an item'\narmor:\n- 'junk'\n")
        assertNull(snapshot.items.single())
        assertNull(snapshot.armor.single())
    }
}
