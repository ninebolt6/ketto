package net.ninebolt.onevsone.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ArenaIdTest {

    @Test
    fun `invalid arena names rejected`() {
        for (bad in listOf("", "a/b", "a\\b", "a.b", "..", "players", "PLAYERS", "Players", "create", "CREATE", "Create", "a\u0000b", "a\u0007b", "x".repeat(65), "a:b", " ab", "ab ")) {
            assertNull(Arena.Id.of(bad))
        }
        assertNotNull(Arena.Id.of("arena-1_2"))
    }
}
