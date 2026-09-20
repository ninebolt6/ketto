package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.PlayerStats
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Server
import org.bukkit.command.CommandSender
import java.util.Locale

/** 状態の色・文字列、Adventure 変換、アイテム表示名をここに集約する。 */
class Messages(prefixRaw: String) {
    private val legacy = LegacyComponentSerializer.legacySection()

    val prefix: String = legacy.serialize(
        LegacyComponentSerializer.legacyAmpersand().deserialize(prefixRaw)
    )

    fun component(text: String): Component = legacy.deserialize(text)

    fun send(sender: CommandSender, text: String) {
        sender.sendMessage(prefix + text)
    }

    fun broadcast(server: Server, text: String) {
        server.broadcast(legacy.deserialize(prefix + text))
    }

    fun stateDisplay(state: ArenaState): String = when (state) {
        ArenaState.WAITING -> "§aWaiting"
        ArenaState.ONEMORE -> "§e1 More"
        ArenaState.COUNTDOWN -> "§cCountdown"
        ArenaState.ROUNDCOUNTDOWN -> "§c§lIngame"
        ArenaState.INGAME -> "§c§lIngame"
    }

    val usageRoot = "§e/1vs1 stats | /1vs1 stats [player]"
    val usageArena = "§e/1vs1 arena info [arena]"
    val noPermission = "§c権限がありません！"
    val playerOnly = "§cこのコマンドはプレイヤーのみ実行可能です"
    val lobbySet = "§aロビーを設定しました"
    fun arenaHeader(name: String) = "§e=== §aArena[§b§l${name}§a] §e==="
    fun arenaState(display: String) = "§e状態: $display"
    fun versus(name1: String, name2: String) = "§c[§6${name1}§c] vs [§6${name2}§c]"
    fun winCount(a: Int, b: Int) = "§e勝数: §a${a}-${b}"
    val arenaExists = "§cそのアリーナはすでに存在しています"
    fun created(name: String) = "§aアリーナ: $name を作成しました"
    val noArena = "§cそのアリーナは存在しません"
    fun removed(name: String) = "§aアリーナ: $name を削除しました"
    fun spawn1Set(name: String) = "§aアリーナ: $name のスポーン1を設定しました"
    fun spawn2Set(name: String) = "§aアリーナ: $name のスポーン2を設定しました"
    fun enabled(name: String) = "§aアリーナ: $name を有効にしました"
    val alreadyEnabled = "§cそのアリーナはすでに有効になっています！"
    fun disabled(name: String) = "§aアリーナ: $name を無効にしました"
    val alreadyDisabled = "§cそのアリーナはすでに無効です！"
    fun inventorySet(name: String) = "§aアリーナ: $name のインベントリを設定しました"
    val lookAtSign = "§c看板を見て実行してください"
    val signTaken = "§cその看板はすでに登録されています"
    val usageCreate = "§c/1vs1 arena create [arena]"
    val usageRemove = "§c/1vs1 arena remove [arena]"
    val usageSetSpawn1 = "§c/1vs1 arena setspawn1 [arena]"
    val usageSetSpawn2 = "§c/1vs1 arena setspawn2 [arena]"
    val usageEnable = "§c/1vs1 arena enable [arena]"
    val usageDisable = "§c/1vs1 arena disable [arena]"
    val usageSetInv = "§c/1vs1 arena setInv [arena]"
    val usageSetSign = "§c/1vs1 arena setsign [arena]"
    val usageRemoveSign = "§c/1vs1 arena removesign [arena]"
    fun signRemoved(name: String) = "§aアリーナ: $name の看板登録を解除しました"
    val signNotRegistered = "§cそのアリーナには看板が登録されていません"
    fun joined(name: String) = "§aアリーナ: $name に参加しました"
    val waitOneMore = "§eあと一人参加するのを待っています。"
    val notEnabled = "§cアリーナが有効になっていません！"
    val alreadyJoined = "§cすでに他のアリーナに参加しています"
    val arenaInGame = "§cこのアリーナは現在ゲーム中です"
    val leftArena = "§cアリーナから退出しました"
    val cannotLeave = "§cカウントダウン中はアリーナから退出できません！"
    val notJoined = "§cあなたはアリーナに参加していません！"
    val commandBlocked = "§cコマンドは使用できません！"
    fun teleportIn(n: Int) = "§aテレポートまで: ${n}秒"
    val gameStart = "§aゲームスタート！"
    fun startIn(n: Int) = "§a開始まで: ${n}秒"
    val roundStart = "§aスタート！"
    fun roundWinner(round: Int, name: String) = "§bラウンド[§6${round}§b] 勝者: $name"
    fun champion(arena: String, name: String) = "§eアリーナ: ${arena}で§c${name}が優勝しました！"
    fun statWin(win: Int) = "§cWin: §b$win"
    fun statLose(lose: Int) = "§cLose: §b$lose"
    fun statRatio(stats: PlayerStats) = "§cW/L(勝率): §b${"%.2f".format(Locale.ROOT, stats.ratio)}"
    val noStats = "§cStatsが存在しません"

    val signTitle = "§4[§6§l1vs1§4]"
    fun signArena(name: String) = "§b$name"
    val signJoin = "§9Join"
    val signCannotJoin = "§4Cannot join"

    fun scoreboardTitle(arena: String) = "§a§l$arena"
    fun scoreboardEntry(name: String) = "§6$name"

    val compassName = "§eGame Selector - Right click to open"
    val featherName = "§cFeather - Right click to Fly (VIP以上のみ)"
}
