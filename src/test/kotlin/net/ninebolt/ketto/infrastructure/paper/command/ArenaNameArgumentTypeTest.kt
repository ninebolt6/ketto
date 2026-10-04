package net.ninebolt.ketto.infrastructure.paper.command

import com.mojang.brigadier.StringReader
import com.mojang.brigadier.arguments.StringArgumentType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ArenaNameArgumentTypeTest {

    @Test
    fun `double quoted names keep inner spaces`() {
        assertEquals("arena 1", ArenaNameArgumentType.parse(StringReader("\"arena 1\"")))
    }

    @Test
    fun `single quoted names keep inner spaces`() {
        assertEquals("arena 1", ArenaNameArgumentType.parse(StringReader("'arena 1'")))
    }

    @Test
    fun `unquoted names stop at the first space`() {
        assertEquals("arena", ArenaNameArgumentType.parse(StringReader("arena next")))
    }

    @Test
    fun `exhausted input parses to an empty name`() {
        assertEquals("", ArenaNameArgumentType.parse(StringReader("")))
    }

    @Test
    fun `native type delegates to a brigadier string type`() {
        assertIs<StringArgumentType>(ArenaNameArgumentType.getNativeType())
    }
}
