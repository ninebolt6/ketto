package net.ninebolt.onevsone.domain

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Arena.Id の名前検証。 */
class ArenaIdTest {

    @Test
    fun `invalid arena names rejected`() {
        for (bad in listOf("", "a/b", "a\\b", "a.b", "..", "players", "PLAYERS", "Players", "a\u0000b", "a\u0007b", "x".repeat(65), "a:b", " ab", "ab ")) {
            assertNull(Arena.Id.of(bad))
            assertThrows(IllegalArgumentException::class.java) { Arena.Id.new(bad) }
        }
        assertNotNull(Arena.Id.of("arena-1_2"))
    }
}
