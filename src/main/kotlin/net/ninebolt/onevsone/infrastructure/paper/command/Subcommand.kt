package net.ninebolt.onevsone.infrastructure.paper.command

import org.bukkit.command.CommandSender

/**
 * /1vs1 のサブコマンド単位。CommandGroup が先頭引数でルーティングし、
 * 権限・引数・存在チェック等の判断は各実装が行う。
 */
internal interface Subcommand {
    /** タブ補完で名前を提案してよいか。実行可否の判定は execute 側の責務。 */
    fun visibleTo(sender: CommandSender): Boolean = sender.isOp

    /** @return 構文エラー時に表示すべき usage。応答送信済みなら null */
    fun execute(sender: CommandSender, args: List<String>): String?

    fun tabComplete(sender: CommandSender, args: List<String>): List<String> = emptyList()
}
