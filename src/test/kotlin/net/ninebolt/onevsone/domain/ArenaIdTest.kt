package net.ninebolt.onevsone.domain

import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

/** Arena.Id の名前検証。 */
class ArenaIdTest {

    @Test
    fun `invalid arena names rejected`() {
        for (bad in listOf("", "a/b", "a\\b", "a.b", "..", "players", "PLAYERS", "Players", "a\u0000b", "a\u0007b", "x".repeat(65), "a:b", " ab", "ab ")) {
            assertNull(Arena.Id.of(bad))
            assertFailsWith<IllegalArgumentException> { Arena.Id.new(bad) }
        }
        assertNotNull(Arena.Id.of("arena-1_2"))
    }
}
