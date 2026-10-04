package net.ninebolt.ketto.domain

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ArenaIdTest {

    @ParameterizedTest(name = "rejects '{0}'")
    @MethodSource("invalidNames")
    fun `invalid arena names rejected`(name: String) {
        assertNull(Arena.Id.of(name))
    }

    @ParameterizedTest(name = "accepts '{0}'")
    @MethodSource("validNames")
    fun `valid arena names accepted`(name: String) {
        assertNotNull(Arena.Id.of(name))
    }

    private companion object {
        @JvmStatic
        fun invalidNames() = listOf("", "create", "CREATE", "Create", "a\u0000b", "a\u0007b", "x".repeat(65), " ab", "ab ")

        @JvmStatic
        fun validNames() = listOf("arena-1_2", "a/b", "a.b", "players", "a:b")
    }
}
