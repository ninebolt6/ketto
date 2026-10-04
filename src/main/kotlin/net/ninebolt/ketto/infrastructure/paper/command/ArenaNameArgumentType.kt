package net.ninebolt.ketto.infrastructure.paper.command

import com.mojang.brigadier.StringReader
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.exceptions.CommandSyntaxException
import io.papermc.paper.command.brigadier.argument.CustomArgumentType

// Arena names allow non-ASCII characters, which brigadier's unquoted word charset rejects
internal object ArenaNameArgumentType : CustomArgumentType<String, String> {

    @Throws(CommandSyntaxException::class)
    override fun parse(reader: StringReader): String = if (reader.canRead() && (reader.peek() == '"' || reader.peek() == '\'')) {
        reader.readQuotedString()
    } else {
        val start = reader.cursor
        while (reader.canRead() && reader.peek() != ' ') reader.skip()
        reader.string.substring(start, reader.cursor)
    }

    override fun getNativeType(): ArgumentType<String> = StringArgumentType.string()
}
