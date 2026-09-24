package net.ninebolt.onevsone.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TeleportRestrictionTest {

    @Test
    fun `unrestricted allows everything`() {
        TeleportTrigger.entries.forEach {
            assertTrue(TeleportRestriction.UNRESTRICTED.allows(it), "trigger=$it")
        }
    }

    @Test
    fun `ender pearl only allows pearl and internal`() {
        assertTrue(TeleportRestriction.ENDER_PEARL_ONLY.allows(TeleportTrigger.ENDER_PEARL))
        assertTrue(TeleportRestriction.ENDER_PEARL_ONLY.allows(TeleportTrigger.INTERNAL))
        assertFalse(TeleportRestriction.ENDER_PEARL_ONLY.allows(TeleportTrigger.EXTERNAL))
    }

    @Test
    fun `plugin only allows internal`() {
        assertTrue(TeleportRestriction.PLUGIN_ONLY.allows(TeleportTrigger.INTERNAL))
        assertFalse(TeleportRestriction.PLUGIN_ONLY.allows(TeleportTrigger.ENDER_PEARL))
        assertFalse(TeleportRestriction.PLUGIN_ONLY.allows(TeleportTrigger.EXTERNAL))
    }
}
