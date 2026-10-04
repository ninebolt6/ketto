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
    fun `ender pearl only allows pearl and plugin`() {
        assertTrue(TeleportRestriction.ENDER_PEARL_ONLY.allows(TeleportTrigger.ENDER_PEARL))
        assertTrue(TeleportRestriction.ENDER_PEARL_ONLY.allows(TeleportTrigger.PLUGIN))
        assertFalse(TeleportRestriction.ENDER_PEARL_ONLY.allows(TeleportTrigger.EXTERNAL))
    }

    @Test
    fun `plugin only allows plugin teleports`() {
        assertTrue(TeleportRestriction.PLUGIN_ONLY.allows(TeleportTrigger.PLUGIN))
        assertFalse(TeleportRestriction.PLUGIN_ONLY.allows(TeleportTrigger.ENDER_PEARL))
        assertFalse(TeleportRestriction.PLUGIN_ONLY.allows(TeleportTrigger.EXTERNAL))
    }
}
