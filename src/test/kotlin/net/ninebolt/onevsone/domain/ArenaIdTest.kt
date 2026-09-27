package net.ninebolt.onevsone.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ArenaIdTest {

    @Test
    fun `invalid arena names rejected`() {
        for (bad in listOf("", "create", "CREATE", "Create", "a\u0000b", "a\u0007b", "x".repeat(65), " ab", "ab ")) {
            assertNull(Arena.Id.of(bad))
        }
        for (good in listOf("arena-1_2", "a/b", "a.b", "players", "a:b")) {
            assertNotNull(Arena.Id.of(good))
        }
    }
}
