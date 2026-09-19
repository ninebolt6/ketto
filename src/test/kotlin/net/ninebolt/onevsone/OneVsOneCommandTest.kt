package net.ninebolt.onevsone

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.command.BlockCommandSender
import org.bukkit.command.Command
import org.bukkit.entity.Player
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentMatchers.contains
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.io.File
import java.util.UUID

class OneVsOneCommandTest {

    @TempDir
    lateinit var folder: File

    private lateinit var env: TestEnv
    private val cmd = mock(Command::class.java)

    @BeforeEach
    fun setup() {
        env = TestEnv(folder)
    }

    @AfterEach
    fun tearDown() {
        env.close()
    }

    private fun run(sender: org.bukkit.command.CommandSender, vararg args: String) =
        env.command.onCommand(sender, cmd, "1vs1", arrayOf(*args))

    private fun writeStats(uuid: UUID, win: Int, lose: Int) {
        repeat(win) { env.store.addWin(uuid) }
        repeat(lose) { env.store.addLose(uuid) }
    }

    private fun opPlayer(name: String): Player {
        val p = env.player(name)
        `when`(p.isOp).thenReturn(true)
        return p
    }

    @Test
    fun `no args shows usage`() {
        val p = env.player("Alice")
        run(p)
        verify(p).sendMessage(contains("/1vs1 stats | /1vs1 stats [player]"))
    }

    @Test
    fun `console cannot run player commands`() {
        val console = mock(BlockCommandSender::class.java)
        run(console, "stats")
        verify(console).sendMessage(contains("このコマンドはプレイヤーのみ実行可能です"))
        run(console, "leave")
        verify(console, org.mockito.Mockito.times(2)).sendMessage(contains("このコマンドはプレイヤーのみ実行可能です"))
    }

    @Test
    fun `non op management commands denied`() {
        val p = env.player("Alice")
        `when`(p.isOp).thenReturn(false)
        run(p, "setlobby")
        verify(p).sendMessage(contains("権限がありません！"))
        run(p, "arena", "create", "x")
        verify(p, org.mockito.Mockito.times(2)).sendMessage(contains("権限がありません！"))
    }

    @Test
    fun `stats shows own stats or missing message`() {
        val p = env.player("Alice")
        run(p, "stats")
        verify(p).sendMessage(contains("Statsが存在しません"))

        writeStats(p.uniqueId, 3, 0)
        run(p, "stats")
        verify(p).sendMessage(contains("Win: §b3"))
        verify(p).sendMessage(contains("Lose: §b0"))
        verify(p).sendMessage(contains("W/L(勝率): §b3.00"))
    }

    @Test
    fun `stats of other online player by exact name`() {
        val viewer = env.player("Viewer")
        val target = env.player("Target")
        writeStats(target.uniqueId, 0, 2)
        run(viewer, "stats", "Target")
        verify(viewer).sendMessage(contains("Win: §b0"))
        verify(viewer).sendMessage(contains("Lose: §b2"))
        verify(viewer).sendMessage(contains("W/L(勝率): §b0.00"))
    }

    @Test
    fun `stats of offline cached player resolves uuid`() {
        val viewer = env.player("Viewer")
        val uuid = UUID.randomUUID()
        writeStats(uuid, 5, 5)
        val offline = mock(org.bukkit.OfflinePlayer::class.java)
        `when`(offline.uniqueId).thenReturn(uuid)
        `when`(env.server.getOfflinePlayerIfCached("Ghost")).thenReturn(offline)
        run(viewer, "stats", "Ghost")
        verify(viewer).sendMessage(contains("W/L(勝率): §b1.00"))
    }

    @Test
    fun `stats offline uncached resolves through future on main thread`() {
        val viewer = env.player("Viewer")
        val uuid = UUID.randomUUID()
        writeStats(uuid, 2, 1)
        val command = OneVsOneCommand(env.plugin, env.service, env.messages) {
            java.util.concurrent.CompletableFuture.completedFuture(uuid)
        }
        command.onCommand(viewer, cmd, "1vs1", arrayOf("stats", "Ghost"))
        verify(viewer, never()).sendMessage(contains("Win:"))
        env.runOneShots()
        verify(viewer).sendMessage(contains("Win: §b2"))
    }

    @Test
    fun `stats offline lookup failure reports no stats`() {
        val viewer = env.player("Viewer")
        val command = OneVsOneCommand(env.plugin, env.service, env.messages) {
            java.util.concurrent.CompletableFuture.failedFuture<UUID>(RuntimeException("lookup failed"))
        }
        command.onCommand(viewer, cmd, "1vs1", arrayOf("stats", "Ghost"))
        env.runOneShots()
        verify(viewer).sendMessage(contains("Statsが存在しません"))
    }

    @Test
    fun `stats offline callback skipped when plugin disabled`() {
        val viewer = env.player("Viewer")
        `when`(env.plugin.isEnabled).thenReturn(false)
        val command = OneVsOneCommand(env.plugin, env.service, env.messages) {
            java.util.concurrent.CompletableFuture.completedFuture(UUID.randomUUID())
        }
        command.onCommand(viewer, cmd, "1vs1", arrayOf("stats", "Ghost"))
        env.runOneShots()
        verify(viewer, never()).sendMessage(org.mockito.ArgumentMatchers.any<net.kyori.adventure.text.Component>())
    }

    @Test
    fun `setlobby writes config`() {
        val p = opPlayer("Alice")
        val w = env.world()
        `when`(p.location).thenReturn(Location(w, 7.5, 64.0, -2.5, 90f, 0f))
        run(p, "setlobby")
        verify(p).sendMessage(contains("ロビーを設定しました"))
        val lobby = env.store.lobby()!!
        assertEquals(7.5, lobby.x)
        assertEquals(90f, lobby.yaw, 0.001f)
    }

    @Test
    fun `arena info shows state and players during match`() {
        val viewer = env.player("Viewer")
        run(viewer, "arena", "info", "missing")
        verify(viewer).sendMessage(contains("そのアリーナは存在しません"))

        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.service.join(p1, arena)
        env.service.join(p2, arena)
        env.tick(6)
        env.service.lose(p2, death = false)

        run(viewer, "arena", "info", "arena1")
        verify(viewer).sendMessage(contains("=== §aArena[§b§larena1§a] §e==="))
        verify(viewer).sendMessage(contains("状態: §c§lIngame"))
        verify(viewer).sendMessage(contains("[§6Alice§c] vs [§6Bob§c]"))
        verify(viewer).sendMessage(contains("勝数: §a1-0"))
    }

    @Test
    fun `arena create remove lifecycle`() {
        val op = opPlayer("Op")
        run(op, "arena", "create", "newarena")
        verify(op).sendMessage(contains("アリーナ: newarena を作成しました"))
        assertEquals(false, env.service.arena("newarena")!!.enabled)

        run(op, "arena", "create", "newarena")
        verify(op).sendMessage(contains("そのアリーナはすでに存在しています"))

        run(op, "arena", "remove", "newarena")
        verify(op).sendMessage(contains("アリーナ: newarena を削除しました"))
        org.junit.jupiter.api.Assertions.assertNull(env.service.arena("newarena"))

        run(op, "arena", "remove", "newarena")
        verify(op).sendMessage(contains("そのアリーナは存在しません"))
    }

    @Test
    fun `arena enable disable`() {
        val op = opPlayer("Op")
        val arena = env.newArena("a2", enabled = false)
        run(op, "arena", "enable", "a2")
        verify(op).sendMessage(contains("を有効にしました"))
        run(op, "arena", "enable", "a2")
        verify(op).sendMessage(contains("そのアリーナはすでに有効になっています！"))
        run(op, "arena", "disable", "a2")
        verify(op).sendMessage(contains("を無効にしました"))
        run(op, "arena", "disable", "a2")
        verify(op).sendMessage(contains("§cそのアリーナはすでに無効です！"))
        assertEquals(false, arena.enabled)
    }

    @Test
    fun `arena setspawn saves fractional location`() {
        val op = opPlayer("Op")
        env.newArena()
        val w = env.world()
        `when`(op.location).thenReturn(Location(w, 1.5, 65.25, -3.0, 33.3f, 12.5f))
        run(op, "arena", "setspawn1", "arena1")
        verify(op).sendMessage(contains("のスポーン1を設定しました"))
        val loaded = env.store.loadArena("arena1")!!
        assertEquals(33.3f, loaded.spawn1!!.yaw, 0.001f)
        assertEquals(65.25, loaded.spawn1!!.y)
    }

    @Test
    fun `arena setInv saves kit`() {
        val op = opPlayer("Op")
        val arena = env.newArena()
        op.inventory.setItem(0, env.item(Material.DIAMOND_SWORD))
        run(op, "arena", "setInv", "arena1")
        verify(op).sendMessage(contains("のインベントリを設定しました"))
        assertEquals(org.bukkit.Material.DIAMOND_SWORD, arena.kit.items[0]?.type)
    }

    @Test
    fun `arena setsign requires looking at sign`() {
        val op = opPlayer("Op")
        env.newArena()
        `when`(op.getTargetBlockExact(10)).thenReturn(null)
        run(op, "arena", "setsign", "arena1")
        verify(op).sendMessage(contains("看板を見て実行してください"))
    }

    @Test
    fun `missing arg shows red usage`() {
        val op = opPlayer("Op")
        run(op, "arena", "create")
        verify(op).sendMessage(contains("§c/1vs1 arena create [arena]"))
        run(op, "arena", "setsign")
        verify(op).sendMessage(contains("§c/1vs1 arena setsign [arena]"))
    }

    @Test
    fun `console can run admin commands`() {
        val console = mock(BlockCommandSender::class.java)
        `when`(console.isOp).thenReturn(true)
        run(console, "arena", "create", "consolearena")
        verify(console).sendMessage(contains("アリーナ: consolearena を作成しました"))
        run(console, "arena", "setspawn1", "consolearena")
        verify(console).sendMessage(contains("このコマンドはプレイヤーのみ実行可能です"))
    }

    @Test
    fun `unknown subcommand falls back to usage`() {
        val p = env.player("Alice")
        run(p, "bogus")
        verify(p).sendMessage(contains("/1vs1 stats | /1vs1 stats [player]"))
        run(p, "arena", "bogus")
        verify(p).sendMessage(contains("/1vs1 arena info [arena]"))
    }
}
