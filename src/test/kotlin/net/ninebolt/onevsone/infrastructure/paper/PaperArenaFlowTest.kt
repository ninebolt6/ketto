package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.application.JoinReply
import net.ninebolt.onevsone.application.LeaveReply
import net.ninebolt.onevsone.application.ToggleReply
import net.ninebolt.onevsone.application.port.BackupRef
import net.ninebolt.onevsone.application.port.PersistenceFailure
import net.ninebolt.onevsone.domain.ArenaDefinition
import net.ninebolt.onevsone.domain.ArenaId
import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.DefeatCause
import net.ninebolt.onevsone.domain.MatchId
import net.ninebolt.onevsone.domain.Participant
import net.ninebolt.onevsone.infrastructure.persistence.PersistedBackup
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.contains
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.io.File
import java.util.UUID

/**
 * 試合進行シナリオの統合テスト。
 * 実装は実物のアダプター + application + domain を通して駆動する。
 */
class PaperArenaFlowTest {

    @TempDir
    lateinit var folder: File

    private lateinit var env: TestEnv

    private val legacy = LegacyComponentSerializer.legacySection()

    @BeforeEach
    fun setup() {
        env = TestEnv(folder)
    }

    @AfterEach
    fun tearDown() {
        env.close()
    }

    private fun serialized(component: Component): String = legacy.serialize(component)

    private fun lastBroadcast(): String {
        val captor = ArgumentCaptor.forClass(Component::class.java)
        verify(env.server).broadcast(captor.capture())
        return serialized(captor.value)
    }

    private fun view(name: String = "arena1") = env.service.matchOf(name)!!

    private fun playersYaml() = YamlConfiguration.loadConfiguration(File(folder, "status/players.yml"))

    @Test
    fun `first join sets ONEMORE second join starts COUNTDOWN`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        env.join(p1, arena)
        assertEquals(ArenaState.ONEMORE, view().state)
        assertEquals(arena, env.service.arenaIdOf(p1.uniqueId))
        verify(p1).sendMessage(contains("に参加しました"))
        verify(p1).sendMessage(contains("あと一人参加するのを待っています"))

        val p2 = env.player("Bob")
        env.join(p2, arena)
        assertEquals(ArenaState.COUNTDOWN, view().state)
        assertEquals(1, env.timers.size)
        assertEquals(10L, env.timers.last().delay)
        assertEquals(20L, env.timers.last().period)
    }

    @Test
    fun `join rejected when already participant or arena disabled or ingame`() {
        val arena = env.newArena()
        val disabled = env.newArena("disabled", enabled = false)
        val p1 = env.player("Alice")
        env.join(p1, disabled)
        verify(p1).sendMessage(contains("アリーナが有効になっていません！"))
        assertNull(env.service.arenaIdOf(p1.uniqueId))

        env.join(p1, arena)
        env.join(p1, arena)
        verify(p1).sendMessage(contains("すでに他のアリーナに参加しています"))

        val p2 = env.player("Bob")
        env.join(p2, arena)
        val p3 = env.player("Carol")
        env.join(p3, arena)
        verify(p3).sendMessage(contains("このアリーナは現在ゲーム中です"))
        assertNull(env.service.arenaIdOf(p3.uniqueId))
    }

    @Test
    fun `initial countdown messages then INGAME with equip teleport scoreboard`() {
        val arena = env.newArena()
        env.setKit(arena, PaperInventorySnapshot(items = listOf(env.item(Material.IRON_SWORD))))
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)

        for (n in 5 downTo 1) {
            env.tick()
            verify(p1).sendMessage(contains("テレポートまで: ${n}秒"))
        }
        assertEquals(ArenaState.COUNTDOWN, view().state)

        env.tick()
        assertEquals(ArenaState.INGAME, view().state)
        verify(p1).sendMessage(contains("ゲームスタート！"))
        verify(p1).teleport(any(Location::class.java))
        verify(p2).teleport(any(Location::class.java))
        assertEquals(Material.IRON_SWORD, p1.inventory.contents[0]?.type)
        assertEquals(Material.IRON_SWORD, p2.inventory.contents[0]?.type)
    }

    @Test
    fun `abort during countdown stops task and leaves waiting inventories untouched`() {
        val arena = env.newArena()
        env.setKit(arena, PaperInventorySnapshot(items = listOf(env.item(Material.IRON_SWORD))))
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(2)
        env.service.abort(arena)
        assertEquals(ArenaState.WAITING, view().state)
        assertTrue(view().participants.isEmpty())
        env.tick(6)
        verify(p1, never()).teleport(any(Location::class.java))
        verify(p2, never()).teleport(any(Location::class.java))
        assertNull(p1.inventory.contents[0])
        verify(p1.inventory, never()).setItem(org.mockito.ArgumentMatchers.eq(0), org.mockito.ArgumentMatchers.argThat<ItemStack?> { it?.type == Material.COMPASS })
    }

    @Test
    fun `nonfinal loss awards round then round countdown restarts INGAME`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)
        assertEquals(ArenaState.INGAME, view().state)

        env.service.defeat(p2.uniqueId, DefeatCause.FALL)
        assertEquals(ArenaState.ROUNDCOUNTDOWN, view().state)
        assertEquals(1, view().winsOf(p1.uniqueId))
        verify(p1).sendMessage(contains("ラウンド["))
        verify(p1).sendMessage(contains("勝者: Alice"))

        env.tick()
        env.tick()
        for (n in 5 downTo 1) {
            env.tick()
            verify(p1).sendMessage(contains("開始まで: ${n}秒"))
        }
        env.tick()
        assertEquals(ArenaState.INGAME, view().state)
        verify(p1).sendMessage(contains("§aスタート！"))
    }

    @Test
    fun `requiredWins 3 ends match on third loss and final kill not counted`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)

        env.service.defeat(p2.uniqueId, DefeatCause.FALL)
        assertEquals(1, view().winsOf(p1.uniqueId))
        env.tick(8)
        env.service.defeat(p2.uniqueId, DefeatCause.FALL)
        assertEquals(2, view().winsOf(p1.uniqueId))
        env.tick(8)
        env.service.defeat(p2.uniqueId, DefeatCause.FALL)

        assertEquals(ArenaState.WAITING, view().state)
        assertTrue(view().wins.isEmpty())
        assertTrue(view().participants.isEmpty())
        assertNull(env.service.arenaIdOf(p1.uniqueId))
        assertNull(env.service.arenaIdOf(p2.uniqueId))
        assertTrue(lastBroadcast().contains("が優勝しました！"))
        assertEquals(1, env.statsRepo.find(p1.uniqueId)!!.wins)
        assertEquals(1, env.statsRepo.find(p2.uniqueId)!!.losses)
    }

    @Test
    fun `requiredWins 1 ends on first loss`() {
        env.close()
        env = TestEnv(folder, requiredWins = 1)
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)
        env.service.defeat(p2.uniqueId, DefeatCause.FALL)
        assertEquals(ArenaState.WAITING, view().state)
        assertTrue(lastBroadcast().contains("Alice"))
    }

    @Test
    fun `mixed winners reach max 5 rounds with requiredWins 3`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)

        val sequence = listOf(p2, p2, p1, p1, p2)
        for ((i, loser) in sequence.withIndex()) {
            env.service.defeat(loser.uniqueId, DefeatCause.FALL)
            if (i < 4) {
                assertEquals(ArenaState.ROUNDCOUNTDOWN, view().state)
                env.tick(8)
                assertEquals(ArenaState.INGAME, view().state)
            }
        }
        assertEquals(ArenaState.WAITING, view().state)
        assertTrue(lastBroadcast().contains("Alice"))
        assertEquals(1, env.statsRepo.find(p1.uniqueId)!!.wins)
    }

    @Test
    fun `environmental death without killer awards opponent`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)

        `when`(p2.isDead).thenReturn(true)
        `when`(p2.killer).thenReturn(null)
        assertTrue(env.service.defeat(p2.uniqueId, DefeatCause.DEATH))
        assertEquals(1, view().winsOf(p1.uniqueId))
        assertEquals(ArenaState.ROUNDCOUNTDOWN, view().state)
    }

    @Test
    fun `death schedules respawn before re-equip`() {
        val arena = env.newArena()
        env.setKit(arena, PaperInventorySnapshot(items = listOf(env.item(Material.IRON_SWORD))))
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)
        p1.inventory.setItem(0, null)

        `when`(p2.isDead).thenReturn(true)
        env.service.defeat(p2.uniqueId, DefeatCause.DEATH)
        assertEquals(1, env.oneShots.size)
        env.runOneShots()
        val order = inOrder(p2.inventory, p2.spigot())
        order.verify(p2.spigot()).respawn()
        order.verify(p2.inventory).setContents(any())
        assertEquals(Material.IRON_SWORD, p2.inventory.contents[0]?.type)
    }

    @Test
    fun `duplicate lose callback does not double score`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)
        `when`(p2.isDead).thenReturn(true)

        assertTrue(env.service.defeat(p2.uniqueId, DefeatCause.DEATH))
        assertFalse(env.service.defeat(p2.uniqueId, DefeatCause.DEATH))
        assertFalse(env.service.defeat(p2.uniqueId, DefeatCause.FALL))
        assertEquals(1, view().winsOf(p1.uniqueId))
        assertNull(env.statsRepo.find(p1.uniqueId))
        assertNull(env.statsRepo.find(p2.uniqueId))
    }

    @Test
    fun `abort during pending respawn never reapplies kit`() {
        val arena = env.newArena()
        env.setKit(arena, PaperInventorySnapshot(items = listOf(env.item(Material.IRON_SWORD))))
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)

        `when`(p2.isDead).thenReturn(true)
        env.service.defeat(p2.uniqueId, DefeatCause.DEATH)
        env.service.abort(arena)
        env.runOneShots()
        assertEquals(Material.COMPASS, p2.inventory.contents[0]?.type)
    }

    @Test
    fun `final death restores original inventory after respawn`() {
        env.close()
        env = TestEnv(folder, requiredWins = 1)
        val arena = env.newArena()
        env.setKit(arena, PaperInventorySnapshot(items = listOf(env.item(Material.IRON_SWORD))))
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        p1.inventory.setItem(0, env.item(Material.BREAD))
        p2.inventory.setItem(0, env.item(Material.APPLE))
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)
        assertEquals(Material.IRON_SWORD, p2.inventory.contents[0]?.type)

        `when`(p2.isDead).thenReturn(true)
        env.service.defeat(p2.uniqueId, DefeatCause.DEATH)
        assertEquals(ArenaState.WAITING, view().state)
        env.runOneShots()
        val order = inOrder(p2.inventory, p2.spigot())
        order.verify(p2.spigot()).respawn()
        assertEquals(Material.APPLE, p2.inventory.contents[0]?.type)
        assertEquals(1, env.statsRepo.find(p1.uniqueId)!!.wins)
        assertEquals(1, env.statsRepo.find(p2.uniqueId)!!.losses)
    }

    @Test
    fun `quit during INGAME forfeits with stats and restores both`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        p1.inventory.setItem(0, env.item(Material.BREAD))
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)

        env.removePlayer(p2)
        env.quit(p2)

        assertEquals(ArenaState.WAITING, view().state)
        assertNull(env.service.arenaIdOf(p1.uniqueId))
        assertNull(env.service.arenaIdOf(p2.uniqueId))
        assertEquals(1, env.statsRepo.find(p1.uniqueId)!!.wins)
        assertEquals(1, env.statsRepo.find(p2.uniqueId)!!.losses)
        assertEquals(Material.BREAD, p1.inventory.contents[0]?.type)
        assertTrue(lastBroadcast().contains("Alice"))
    }

    @Test
    fun `quit during COUNTDOWN forfeits`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)
        env.removePlayer(p1)
        env.quit(p1)
        assertEquals(ArenaState.WAITING, view().state)
        assertEquals(1, env.statsRepo.find(p2.uniqueId)!!.wins)
        assertEquals(1, env.statsRepo.find(p1.uniqueId)!!.losses)
    }

    @Test
    fun `quit during ONEMORE unregisters without stats`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        p1.inventory.setItem(0, env.item(Material.BREAD))
        env.join(p1, arena)
        env.removePlayer(p1)
        env.quit(p1)
        assertEquals(ArenaState.WAITING, view().state)
        assertNull(env.service.arenaIdOf(p1.uniqueId))
        assertNull(env.statsRepo.find(p1.uniqueId))
        assertEquals(Material.BREAD, p1.inventory.contents[0]?.type)
    }

    @Test
    fun `leave only allowed in ONEMORE`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        assertEquals(LeaveReply.NotJoined, env.leave(p1))
        verify(p1).sendMessage(contains("あなたはアリーナに参加していません！"))

        env.join(p1, arena)
        env.join(p2, arena)
        assertEquals(LeaveReply.NotWaiting, env.leave(p1))
        verify(p1).sendMessage(contains("カウントダウン中はアリーナから退出できません！"))
        assertEquals(arena, env.service.arenaIdOf(p1.uniqueId))
    }

    @Test
    fun `leave in ONEMORE resets arena without touching inventory`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        p1.inventory.setItem(0, env.item(Material.BREAD))
        env.join(p1, arena)
        assertEquals(LeaveReply.Left, env.leave(p1))
        verify(p1).sendMessage(contains("アリーナから退出しました"))
        assertEquals(ArenaState.WAITING, view().state)
        assertNull(env.service.arenaIdOf(p1.uniqueId))
        assertEquals(Material.BREAD, p1.inventory.contents[0]?.type)
    }

    @Test
    fun `empty snapshot falls back to lobby items on restore`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)
        env.removePlayer(p1)
        env.quit(p1)
        verify(p1.inventory).setItem(org.mockito.ArgumentMatchers.eq(0), org.mockito.ArgumentMatchers.argThat<ItemStack?> { it?.type == Material.COMPASS })
        verify(p1.inventory).setItem(org.mockito.ArgumentMatchers.eq(8), org.mockito.ArgumentMatchers.argThat<ItemStack?> { it?.type == Material.FEATHER })
    }

    @Test
    fun `empty kit does not grant lobby items`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)
        assertNull(p1.inventory.contents[0])
        verify(p1.inventory, never()).setItem(org.mockito.ArgumentMatchers.eq(0), org.mockito.ArgumentMatchers.argThat<ItemStack?> { it?.type == Material.COMPASS })
    }

    @Test
    fun `shutdown preserves enabled and clears state`() {
        env.newArena()
        val p1 = env.player("Alice")
        env.join(p1, ArenaId("arena1"))
        env.service.shutdown()
        assertTrue(env.service.definition("arena1")!!.enabled)
        assertEquals(ArenaState.WAITING, view().state)
        assertTrue(view().participants.isEmpty())
        assertNull(env.service.arenaIdOf(p1.uniqueId))
    }

    @Test
    fun `disable aborts and clears registration while persisting disabled`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)
        assertEquals(ToggleReply.Changed, env.admin.setEnabled("arena1", false))
        assertFalse(env.service.definition("arena1")!!.enabled)
        assertEquals(ArenaState.WAITING, view().state)
        assertNull(env.service.arenaIdOf(p1.uniqueId))
        assertNull(env.service.arenaIdOf(p2.uniqueId))
        env.tick(6)
        verify(p1, never()).teleport(any(Location::class.java))
        val reloaded = env.arenaRepo.find("arena1")!!
        assertFalse(reloaded.enabled)
    }

    @Test
    fun `enabled persists across service load`() {
        env.arenaRepo.save(ArenaDefinition(ArenaId("arena1"), enabled = true))
        env.arenaRepo.saveArenaNames(listOf("arena1"))
        env.service.load()
        assertTrue(env.service.definition("arena1")!!.enabled)
    }

    @Test
    fun `pending restore applied on join and discarded`() {
        val uuid = UUID.randomUUID()
        val ref = BackupRef(UUID.randomUUID(), MatchId.newId(), uuid, "Alice")
        env.matchStateRepo.registerParticipant(Participant(uuid, "Alice"), ArenaId("a1"))
        env.store.saveBackups(listOf(PersistedBackup(ref, PaperInventorySnapshot())))
        env.matchStateRepo.clearRegistrations()
        env.service.load()

        val p = env.player("Alice", uuid)
        p.inventory.setItem(0, env.item(Material.STONE))
        env.service.restorePending(p.uniqueId, p.name)
        verify(p.inventory).setItem(org.mockito.ArgumentMatchers.eq(0), org.mockito.ArgumentMatchers.argThat<ItemStack?> { it?.type == Material.COMPASS })
        assertNull(playersYaml().getConfigurationSection("inv.Alice"))
    }

    @Test
    fun `final death quit before respawn tick still restores original inventory`() {
        env.close()
        env = TestEnv(folder, requiredWins = 1)
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        p2.inventory.setItem(0, env.item(Material.APPLE))
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)

        `when`(p2.isDead).thenReturn(true)
        env.service.defeat(p2.uniqueId, DefeatCause.DEATH)
        assertEquals(ArenaState.WAITING, view().state)

        env.removePlayer(p2)
        env.quit(p2)
        assertEquals(Material.APPLE, p2.inventory.contents[0]?.type)

        `when`(p2.isOnline).thenReturn(true)
        `when`(p2.isDead).thenReturn(false)
        env.players[p2.uniqueId] = p2
        p2.inventory.setItem(0, env.item(Material.GOLDEN_APPLE))
        env.runOneShots()
        assertEquals(Material.GOLDEN_APPLE, p2.inventory.contents[0]?.type)
    }

    @Test
    fun `final death shutdown retains record and rejoin reapplies`() {
        env.close()
        env = TestEnv(folder, requiredWins = 1)
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        p2.inventory.setItem(0, env.item(Material.APPLE))
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)

        `when`(p2.isDead).thenReturn(true)
        env.service.defeat(p2.uniqueId, DefeatCause.DEATH)
        env.service.shutdown()

        assertEquals(Material.APPLE, p2.inventory.contents[0]?.type)
        assertEquals(p2.uniqueId.toString(), playersYaml().getString("inv.Bob.uuid"))

        `when`(p2.isDead).thenReturn(false)
        env.service.restorePending(p2.uniqueId, p2.name)
        assertEquals(Material.APPLE, p2.inventory.contents[0]?.type)
        assertNull(playersYaml().getConfigurationSection("inv.Bob"))

        env.runOneShots()
        assertEquals(Material.APPLE, p2.inventory.contents[0]?.type)
    }

    @Test
    fun `abort dead player restores on next tick and stale callback cannot reapply kit`() {
        val arena = env.newArena()
        env.setKit(arena, PaperInventorySnapshot(items = listOf(env.item(Material.IRON_SWORD))))
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        p2.inventory.setItem(0, env.item(Material.APPLE))
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)

        `when`(p2.isDead).thenReturn(true)
        env.service.defeat(p2.uniqueId, DefeatCause.DEATH)
        env.service.abort(arena)
        env.runOneShots()
        assertEquals(Material.APPLE, p2.inventory.contents[0]?.type)

        `when`(p2.isDead).thenReturn(false)
        env.join(p2, arena)
        assertEquals(arena, env.service.arenaIdOf(p2.uniqueId))
        assertEquals(Material.APPLE, p2.inventory.contents[0]?.type)
    }

    @Test
    fun `abort retains pending restore for offline participant`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        p2.inventory.setItem(0, env.item(Material.APPLE))
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)

        env.removePlayer(p2)
        env.service.abort(arena)

        assertEquals(p2.uniqueId.toString(), playersYaml().getString("inv.Bob.uuid"))

        `when`(p2.isOnline).thenReturn(true)
        env.players[p2.uniqueId] = p2
        env.service.restorePending(p2.uniqueId, p2.name)
        assertEquals(Material.APPLE, p2.inventory.contents[0]?.type)
        assertNull(playersYaml().getConfigurationSection("inv.Bob"))
    }

    @Test
    fun `name collision does not restore and renamed uuid does`() {
        val arena = env.newArena()
        val uuid = UUID.randomUUID()
        val original = env.player("Alice", uuid)
        val bob = env.player("Bob")
        original.inventory.setItem(0, env.item(Material.DIAMOND))
        env.join(original, arena)
        env.join(bob, arena)
        env.tick(6)
        env.removePlayer(original)
        env.service.abort(arena)

        val squatter = env.player("Alice", UUID.randomUUID())
        squatter.inventory.setItem(0, env.item(Material.STONE))
        env.service.restorePending(squatter.uniqueId, squatter.name)
        assertEquals(Material.STONE, squatter.inventory.contents[0]?.type)

        val renamed = env.player("Alice2", uuid)
        env.service.restorePending(renamed.uniqueId, renamed.name)
        assertEquals(Material.DIAMOND, renamed.inventory.contents[0]?.type)
        assertNull(playersYaml().getConfigurationSection("inv.Alice"))
    }

    @Test
    fun `join refused when participant snapshot cannot be persisted`() {
        env.close()
        val broken = File(folder, "broken")
        broken.mkdirs()
        File(broken, "status").writeText("not a directory")
        env = TestEnv(broken)
        val arena = env.newArena()
        val p = env.player("Alice")
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
            env.service.join(p.uniqueId, p.name, arena)
        }
        assertNull(env.service.arenaIdOf(p.uniqueId))
        assertTrue(view().participants.isEmpty())
        assertEquals(ArenaState.WAITING, view().state)
    }

    @Test
    fun `void loss in ROUNDCOUNTDOWN scores again after guard release and cancels old timer`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)

        assertTrue(env.service.defeat(p2.uniqueId, DefeatCause.FALL))
        val roundTimerId = env.timers.last().taskId
        assertEquals(ArenaState.ROUNDCOUNTDOWN, view().state)

        assertFalse(env.service.defeat(p2.uniqueId, DefeatCause.FALL))
        assertEquals(1, view().winsOf(p1.uniqueId))

        env.runOneShots()
        assertTrue(env.service.defeat(p2.uniqueId, DefeatCause.FALL))
        assertEquals(2, view().winsOf(p1.uniqueId))
        assertEquals(ArenaState.ROUNDCOUNTDOWN, view().state)

        env.timers.first { it.taskId == roundTimerId }.runnable.run()
        assertTrue(env.cancelledTaskIds.contains(roundTimerId))
    }

    @Test
    fun `quit during ROUNDCOUNTDOWN forfeits with stats`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)
        env.service.defeat(p2.uniqueId, DefeatCause.FALL)
        assertEquals(ArenaState.ROUNDCOUNTDOWN, view().state)

        env.removePlayer(p1)
        env.quit(p1)
        assertEquals(ArenaState.WAITING, view().state)
        assertEquals(1, env.statsRepo.find(p2.uniqueId)!!.wins)
        assertEquals(1, env.statsRepo.find(p1.uniqueId)!!.losses)
    }

    @Test
    fun `finish resets vitals and clears sidebars`() {
        env.close()
        env = TestEnv(folder, requiredWins = 1)
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)

        env.service.defeat(p2.uniqueId, DefeatCause.FALL)
        verify(p1, org.mockito.Mockito.times(2)).setHealth(20.0)
        verify(p1, org.mockito.Mockito.times(2)).setFoodLevel(20)
        verify(p1, org.mockito.Mockito.times(2)).setFireTicks(0)
        verify(p2, org.mockito.Mockito.times(2)).setHealth(20.0)
        verify(p2, org.mockito.Mockito.times(2)).setScoreboard(any())
        verify(p1, org.mockito.Mockito.times(2)).setScoreboard(any())
    }

    @Test
    fun `abort clears sidebar`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)
        env.service.abort(arena)
        verify(p1, org.mockito.Mockito.times(2)).setScoreboard(any())
        verify(p2, org.mockito.Mockito.times(2)).setScoreboard(any())
    }

    @Test
    fun `createArena rejects case insensitive duplicates`() {
        assertTrue(env.admin.create("Arena1"))
        assertFalse(env.admin.create("arena1"))
        assertFalse(env.admin.create("PLAYERS"))
    }

    @Test
    fun `join writes membership only and never reads waiting inventory`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        env.join(p1, arena)
        verify(p1.inventory, never()).contents
        verify(p1.inventory, never()).armorContents
        verify(p1.inventory, never()).clear()
        val yaml = playersYaml()
        assertTrue(yaml.getStringList("players").contains("Alice"))
        assertEquals("arena1", yaml.getString("arena.Alice"))
        assertNull(yaml.getConfigurationSection("inv.Alice"))
    }

    @Test
    fun `waiting leave does not recreate transferred items and keeps received items`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        p1.inventory.setItem(0, env.item(Material.DIAMOND))
        env.join(p1, arena)
        p1.inventory.setItem(0, null)
        env.leave(p1)
        assertNull(p1.inventory.contents[0])
        verify(p1.inventory, never()).setItem(org.mockito.ArgumentMatchers.eq(0), org.mockito.ArgumentMatchers.argThat<ItemStack?> { it?.type == Material.COMPASS })

        val p2 = env.player("Bob")
        env.join(p2, arena)
        p2.inventory.setItem(0, env.item(Material.APPLE))
        env.leave(p2)
        assertEquals(Material.APPLE, p2.inventory.contents[0]?.type)
    }

    @Test
    fun `waiting quit disable and shutdown preserve current inventory`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        p1.inventory.setItem(0, env.item(Material.BREAD))
        env.join(p1, arena)
        env.removePlayer(p1)
        env.quit(p1)
        assertEquals(Material.BREAD, p1.inventory.contents[0]?.type)
        assertNull(env.service.pendingRestore(p1.uniqueId))

        val p2 = env.player("Bob")
        p2.inventory.setItem(0, env.item(Material.APPLE))
        env.join(p2, arena)
        env.admin.setEnabled("arena1", false)
        assertEquals(Material.APPLE, p2.inventory.contents[0]?.type)
        assertNull(env.service.pendingRestore(p2.uniqueId))
        env.admin.setEnabled("arena1", true)

        val p3 = env.player("Carol")
        p3.inventory.setItem(0, env.item(Material.COOKED_BEEF))
        env.join(p3, arena)
        env.service.shutdown()
        assertEquals(Material.COOKED_BEEF, p3.inventory.contents[0]?.type)
    }

    @Test
    fun `snapshot captured at match start reflects countdown window changes`() {
        val arena = env.newArena()
        env.setKit(arena, PaperInventorySnapshot(items = listOf(env.item(Material.IRON_SWORD))))
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        p1.inventory.setItem(0, env.item(Material.BREAD))
        env.join(p1, arena)
        env.join(p2, arena)
        p1.inventory.setItem(0, env.item(Material.APPLE))
        env.tick(5)
        assertNull(playersYaml().getConfigurationSection("inv.Alice"))
        env.tick()
        assertEquals(ArenaState.INGAME, view().state)
        assertEquals(Material.IRON_SWORD, p1.inventory.contents[0]?.type)
        val yaml = playersYaml()
        assertEquals(p1.uniqueId.toString(), yaml.getString("inv.Alice.uuid"))
        assertEquals(p2.uniqueId.toString(), yaml.getString("inv.Bob.uuid"))
        env.service.abort(arena)
        assertEquals(Material.APPLE, p1.inventory.contents[0]?.type)
    }

    @Test
    fun `round kit reapplications never overwrite saved originals`() {
        val arena = env.newArena()
        env.setKit(arena, PaperInventorySnapshot(items = listOf(env.item(Material.IRON_SWORD))))
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        p2.inventory.setItem(0, env.item(Material.APPLE))
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)

        env.service.defeat(p2.uniqueId, DefeatCause.FALL)
        env.runOneShots()
        env.tick(8)
        assertEquals(ArenaState.INGAME, view().state)
        env.service.defeat(p2.uniqueId, DefeatCause.FALL)
        env.runOneShots()
        env.tick(8)
        assertEquals(ArenaState.INGAME, view().state)
        env.service.defeat(p2.uniqueId, DefeatCause.FALL)
        assertEquals(ArenaState.WAITING, view().state)
        assertEquals(Material.APPLE, p2.inventory.contents[0]?.type)
    }

    @Test
    fun `quit during COUNTDOWN forfeits without touching inventories`() {
        val arena = env.newArena()
        env.setKit(arena, PaperInventorySnapshot(items = listOf(env.item(Material.IRON_SWORD))))
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        p1.inventory.setItem(0, env.item(Material.BREAD))
        p2.inventory.setItem(0, env.item(Material.APPLE))
        env.join(p1, arena)
        env.join(p2, arena)
        env.removePlayer(p1)
        env.quit(p1)
        assertEquals(ArenaState.WAITING, view().state)
        assertEquals(Material.BREAD, p1.inventory.contents[0]?.type)
        assertEquals(Material.APPLE, p2.inventory.contents[0]?.type)
        assertEquals(1, env.statsRepo.find(p2.uniqueId)!!.wins)
        assertEquals(1, env.statsRepo.find(p1.uniqueId)!!.losses)
        val yaml = playersYaml()
        assertNull(yaml.getConfigurationSection("inv.Alice"))
        assertNull(yaml.getConfigurationSection("inv.Bob"))
    }

    @Test
    fun `snapshot persistence failure aborts match before equipment`() {
        val spyStore = org.mockito.Mockito.spy(env.store)
        org.mockito.Mockito.doThrow(PersistenceFailure("disk full")).`when`(spyStore)
            .saveBackups(org.mockito.ArgumentMatchers.anyList())
        env.rebuildWith(spyStore)
        val arena = env.newArena("spy-arena", enabled = true)
        env.setKit(arena, PaperInventorySnapshot(items = listOf(env.item(Material.IRON_SWORD))))
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        p1.inventory.setItem(0, env.item(Material.BREAD))
        assertEquals(JoinReply.JoinedWaiting, env.service.join(p1.uniqueId, p1.name, arena))
        assertEquals(JoinReply.JoinedStarting, env.service.join(p2.uniqueId, p2.name, arena))
        env.tick(6)
        assertEquals(ArenaState.WAITING, env.service.matchOf("spy-arena")!!.state)
        assertTrue(env.service.matchOf("spy-arena")!!.participants.isEmpty())
        assertNull(env.service.arenaIdOf(p1.uniqueId))
        assertNull(env.service.arenaIdOf(p2.uniqueId))
        verify(p1.inventory, never()).clear()
        verify(p2.inventory, never()).clear()
        assertEquals(Material.BREAD, p1.inventory.contents[0]?.type)
        assertNull(p2.inventory.contents[0])
        env.tick(3)
        verify(p1, never()).teleport(any(Location::class.java))
    }

    @Test
    fun `abort completes memory cleanup even when unregister disk write fails`() {
        val spyMatchState = org.mockito.Mockito.spy(env.matchStateRepo)
        org.mockito.Mockito.doThrow(PersistenceFailure("io")).`when`(spyMatchState)
            .unregisterParticipant(org.mockito.ArgumentMatchers.anyString())
        env.rebuildWith(matchState = spyMatchState)
        val arena = env.newArena("spy-arena", enabled = true)
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.service.join(p1.uniqueId, p1.name, arena)
        env.service.join(p2.uniqueId, p2.name, arena)
        env.service.abort(arena)
        assertEquals(ArenaState.WAITING, env.service.matchOf("spy-arena")!!.state)
        assertTrue(env.service.matchOf("spy-arena")!!.participants.isEmpty())
        assertNull(env.service.arenaIdOf(p1.uniqueId))
        assertNull(env.service.arenaIdOf(p2.uniqueId))
    }

    @Test
    fun `malformed winner stats does not prevent final death cleanup`() {
        env.close()
        env = TestEnv(folder, requiredWins = 1)
        val logger = org.mockito.Mockito.mock(java.util.logging.Logger::class.java)
        `when`(env.plugin.logger).thenReturn(logger)
        val arena = env.newArena()
        env.setKit(arena, PaperInventorySnapshot(items = listOf(env.item(Material.IRON_SWORD))))
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        p1.inventory.setItem(0, env.item(Material.BREAD))
        p2.inventory.setItem(0, env.item(Material.APPLE))
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)
        val winnerStats = File(folder, "stats/${p1.uniqueId}.yml")
        winnerStats.writeText("win: [broken")
        `when`(p2.isDead).thenReturn(true)

        org.junit.jupiter.api.Assertions.assertDoesNotThrow { env.service.defeat(p2.uniqueId, DefeatCause.DEATH) }
        assertEquals(ArenaState.WAITING, view().state)
        assertEquals(Material.BREAD, p1.inventory.contents[0]?.type)
        env.runOneShots()
        assertEquals(Material.APPLE, p2.inventory.contents[0]?.type)
        assertNull(env.service.arenaIdOf(p1.uniqueId))
        assertNull(env.service.arenaIdOf(p2.uniqueId))
        verify(p1, org.mockito.Mockito.times(2)).setScoreboard(any())
        assertEquals("win: [broken", winnerStats.readText())
        assertEquals(1, env.statsRepo.find(p2.uniqueId)!!.losses)
        verify(logger, org.mockito.Mockito.times(1)).log(
            org.mockito.ArgumentMatchers.eq(java.util.logging.Level.SEVERE),
            contains("Failed to record"),
            org.mockito.ArgumentMatchers.any(Throwable::class.java)
        )
        val statusYaml = YamlConfiguration.loadConfiguration(File(folder, "status/arena1.yml"))
        assertEquals("WAITING", statusYaml.getString("status"))
        assertTrue(statusYaml.getStringList("players").isEmpty())
    }

    @Test
    fun `malformed loser stats does not prevent final cleanup`() {
        env.close()
        env = TestEnv(folder, requiredWins = 1)
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        p1.inventory.setItem(0, env.item(Material.BREAD))
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(6)
        File(folder, "stats/${p2.uniqueId}.yml").writeText("lose: [broken")

        org.junit.jupiter.api.Assertions.assertDoesNotThrow { env.service.defeat(p2.uniqueId, DefeatCause.FALL) }
        assertEquals(ArenaState.WAITING, view().state)
        assertEquals(Material.BREAD, p1.inventory.contents[0]?.type)
        assertEquals(1, env.statsRepo.find(p1.uniqueId)!!.wins)
    }

    @Test
    fun `stats write failure does not prevent final cleanup`() {
        env.close()
        env = TestEnv(folder, requiredWins = 1)
        val spyStats = org.mockito.Mockito.spy(env.statsRepo)
        env.rebuildWith(statsRepo = spyStats)
        val arena = env.newArena("spy-arena", enabled = true)
        env.setKit(arena, PaperInventorySnapshot(items = listOf(env.item(Material.IRON_SWORD))))
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        p1.inventory.setItem(0, env.item(Material.BREAD))
        p2.inventory.setItem(0, env.item(Material.APPLE))
        env.service.join(p1.uniqueId, p1.name, arena)
        env.service.join(p2.uniqueId, p2.name, arena)
        val winnerId = p1.uniqueId
        org.mockito.Mockito.doThrow(IllegalStateException("write failed", java.io.IOException("disk full")))
            .`when`(spyStats).recordWin(winnerId)
        env.tick(6)

        org.junit.jupiter.api.Assertions.assertDoesNotThrow { env.service.defeat(p2.uniqueId, DefeatCause.FALL) }
        assertEquals(ArenaState.WAITING, env.service.matchOf("spy-arena")!!.state)
        assertEquals(Material.BREAD, p1.inventory.contents[0]?.type)
        assertEquals(Material.APPLE, p2.inventory.contents[0]?.type)
        assertNull(env.service.arenaIdOf(p1.uniqueId))
        assertNull(env.service.arenaIdOf(p2.uniqueId))
        assertEquals(1, env.statsRepo.find(p2.uniqueId)!!.losses)
        assertFalse(File(folder, "stats/${p1.uniqueId}.yml").exists())
    }

    @Test
    fun `stats failure during COUNTDOWN forfeit still completes cleanup`() {
        env.close()
        env = TestEnv(folder)
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        p1.inventory.setItem(0, env.item(Material.BREAD))
        p2.inventory.setItem(0, env.item(Material.APPLE))
        env.join(p1, arena)
        env.join(p2, arena)
        File(folder, "stats/${p1.uniqueId}.yml").writeText("lose: [broken")
        env.removePlayer(p1)

        org.junit.jupiter.api.Assertions.assertDoesNotThrow { env.quit(p1) }
        assertEquals(ArenaState.WAITING, view().state)
        assertNull(env.service.arenaIdOf(p2.uniqueId))
        assertEquals(Material.BREAD, p1.inventory.contents[0]?.type)
        assertEquals(Material.APPLE, p2.inventory.contents[0]?.type)
        assertEquals(1, env.statsRepo.find(p2.uniqueId)!!.wins)
    }

    @Test
    fun `participant vanishing during countdown aborts before touching inventory`() {
        val arena = env.newArena()
        env.setKit(arena, PaperInventorySnapshot(items = listOf(env.item(Material.IRON_SWORD))))
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        p1.inventory.setItem(0, env.item(Material.BREAD))
        p2.inventory.setItem(0, env.item(Material.APPLE))
        env.join(p1, arena)
        env.join(p2, arena)
        env.tick(5)

        // 開始直前にプレイヤーが消える → バックアップも装備交換も行わず中断
        env.removePlayer(p2)
        env.tick()
        assertEquals(ArenaState.WAITING, view().state)
        assertEquals(Material.BREAD, p1.inventory.contents[0]?.type)
        verify(p1.inventory, never()).clear()
        assertNull(env.service.arenaIdOf(p1.uniqueId))
    }
}
