package net.ninebolt.ketto.infrastructure.persistence

import net.ninebolt.ketto.application.port.PersistenceException
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
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
    fun `codec rejects a non item entry in the items list`() {
        assertFailsWith<PersistenceException> {
            InventoryPayloadCodec.decode("item:\n- 'not an item'\n")
        }
    }

    @Test
    fun `codec rejects a non item entry in the armor list`() {
        assertFailsWith<PersistenceException> {
            InventoryPayloadCodec.decode("armor:\n- 'junk'\n")
        }
    }
}
