package net.ninebolt.onevsone

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Location
import org.bukkit.Material
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
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.ArgumentCaptor
import java.io.File
import java.util.UUID

class ArenaServiceTest {

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

    @Test
    fun `first join sets ONEMORE second join starts COUNTDOWN`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        env.service.join(p1, arena)
        assertEquals(ArenaState.ONEMORE, arena.state)
        assertEquals(arena, env.service.arenaOf(p1.uniqueId))
        verify(p1).sendMessage(org.mockito.ArgumentMatchers.contains("に参加しました"))
        verify(p1).sendMessage(org.mockito.ArgumentMatchers.contains("あと一人参加するのを待っています"))

        val p2 = env.player("Bob")
        env.service.join(p2, arena)
        assertEquals(ArenaState.COUNTDOWN, arena.state)
        assertEquals(1, env.timers.size)
        assertEquals(10L, env.timers.last().delay)
        assertEquals(20L, env.timers.last().period)
    }

    @Test
    fun `join rejected when already participant or arena disabled or ingame`() {
        val arena = env.newArena()
        val disabled = env.newArena("disabled", enabled = false)
        val p1 = env.player("Alice")
        env.service.join(p1, disabled)
        verify(p1).sendMessage(org.mockito.ArgumentMatchers.contains("アリーナが有効になっていません！"))
        assertNull(env.service.arenaOf(p1.uniqueId))

        env.service.join(p1, arena)
        env.service.join(p1, arena)
        verify(p1).sendMessage(org.mockito.ArgumentMatchers.contains("すでに他のアリーナに参加しています"))

        val p2 = env.player("Bob")
        env.service.join(p2, arena)
        val p3 = env.player("Carol")
        env.service.join(p3, arena)
        verify(p3).sendMessage(org.mockito.ArgumentMatchers.contains("このアリーナは現在ゲーム中です"))
        assertNull(env.service.arenaOf(p3.uniqueId))
    }

    @Test
    fun `initial countdown messages then INGAME with equip teleport scoreboard`() {
        val arena = env.newArena()
        arena.kit = InventorySnapshot(items = listOf(env.item(Material.IRON_SWORD)))
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.service.join(p1, arena)
        env.service.join(p2, arena)

        for (n in 5 downTo 1) {
            env.tick()
            verify(p1).sendMessage(org.mockito.ArgumentMatchers.contains("テレポートまで: ${n}秒"))
        }
        assertEquals(ArenaState.COUNTDOWN, arena.state)

        env.tick()
        assertEquals(ArenaState.INGAME, arena.state)
        verify(p1).sendMessage(org.mockito.ArgumentMatchers.contains("ゲームスタート！"))
        verify(p1).teleport(any(Location::class.java))
        verify(p2).teleport(any(Location::class.java))
        assertEquals(Material.IRON_SWORD, p1.inventory.contents[0]?.type)
        assertEquals(Material.IRON_SWORD, p2.inventory.contents[0]?.type)
    }

    @Test
    fun `abort during countdown stops task and prevents equipment`() {
        val arena = env.newArena()
        arena.kit = InventorySnapshot(items = listOf(env.item(Material.IRON_SWORD)))
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.service.join(p1, arena)
        env.service.join(p2, arena)
        env.tick(2)
        env.service.abort(arena)
        assertEquals(ArenaState.WAITING, arena.state)
        assertTrue(arena.players.isEmpty())
        env.tick(6)
        verify(p1, never()).teleport(any(Location::class.java))
        verify(p2, never()).teleport(any(Location::class.java))
        assertEquals(Material.COMPASS, p1.inventory.contents[0]?.type)
    }

    @Test
    fun `nonfinal loss awards round then round countdown restarts INGAME`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.service.join(p1, arena)
        env.service.join(p2, arena)
        env.tick(6)
        assertEquals(ArenaState.INGAME, arena.state)

        env.service.lose(p2, death = false)
        assertEquals(ArenaState.ROUNDCOUNTDOWN, arena.state)
        assertEquals(1, arena.wins[p1.uniqueId])
        verify(p1).sendMessage(org.mockito.ArgumentMatchers.contains("ラウンド["))
        verify(p1).sendMessage(org.mockito.ArgumentMatchers.contains("勝者: Alice"))

        env.tick()
        env.tick()
        for (n in 5 downTo 1) {
            env.tick()
            verify(p1).sendMessage(org.mockito.ArgumentMatchers.contains("開始まで: ${n}秒"))
        }
        env.tick()
        assertEquals(ArenaState.INGAME, arena.state)
        verify(p1).sendMessage(org.mockito.ArgumentMatchers.contains("§aスタート！"))
    }

    @Test
    fun `requiredWins 3 ends match on third loss and final kill not counted`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.service.join(p1, arena)
        env.service.join(p2, arena)
        env.tick(6)

        env.service.lose(p2, death = false)
        assertEquals(1, arena.wins[p1.uniqueId])
        env.tick(8)
        env.service.lose(p2, death = false)
        assertEquals(2, arena.wins[p1.uniqueId])
        env.tick(8)
        env.service.lose(p2, death = false)

        assertEquals(ArenaState.WAITING, arena.state)
        assertTrue(arena.wins.isEmpty())
        assertTrue(arena.players.isEmpty())
        assertNull(env.service.arenaOf(p1.uniqueId))
        assertNull(env.service.arenaOf(p2.uniqueId))
        assertTrue(lastBroadcast().contains("が優勝しました！"))
        assertEquals(1, env.store.readStats(p1.uniqueId).first)
        assertEquals(1, env.store.readStats(p2.uniqueId).second)
    }

    @Test
    fun `requiredWins 1 ends on first loss`() {
        env.close()
        env = TestEnv(folder, requiredWins = 1)
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.service.join(p1, arena)
        env.service.join(p2, arena)
        env.tick(6)
        env.service.lose(p2, death = false)
        assertEquals(ArenaState.WAITING, arena.state)
        assertTrue(lastBroadcast().contains("Alice"))
    }

    @Test
    fun `mixed winners reach max 5 rounds with requiredWins 3`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.service.join(p1, arena)
        env.service.join(p2, arena)
        env.tick(6)

        val sequence = listOf(p2, p2, p1, p1, p2)
        for ((i, loser) in sequence.withIndex()) {
            env.service.lose(loser, death = false)
            if (i < 4) {
                assertEquals(ArenaState.ROUNDCOUNTDOWN, arena.state)
                env.tick(8)
                assertEquals(ArenaState.INGAME, arena.state)
            }
        }
        assertEquals(ArenaState.WAITING, arena.state)
        assertTrue(lastBroadcast().contains("Alice"))
        assertEquals(1, env.store.readStats(p1.uniqueId).first)
    }

    @Test
    fun `environmental death without killer awards opponent`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.service.join(p1, arena)
        env.service.join(p2, arena)
        env.tick(6)

        `when`(p2.isDead).thenReturn(true)
        `when`(p2.killer).thenReturn(null)
        assertTrue(env.service.lose(p2, death = true))
        assertEquals(1, arena.wins[p1.uniqueId])
        assertEquals(ArenaState.ROUNDCOUNTDOWN, arena.state)
    }

    @Test
    fun `death schedules respawn before re-equip`() {
        val arena = env.newArena()
        arena.kit = InventorySnapshot(items = listOf(env.item(Material.IRON_SWORD)))
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.service.join(p1, arena)
        env.service.join(p2, arena)
        env.tick(6)
        p1.inventory.setItem(0, null)

        `when`(p2.isDead).thenReturn(true)
        env.service.lose(p2, death = true)
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
        env.service.join(p1, arena)
        env.service.join(p2, arena)
        env.tick(6)
        `when`(p2.isDead).thenReturn(true)

        assertTrue(env.service.lose(p2, death = true))
        assertFalse(env.service.lose(p2, death = true))
        assertFalse(env.service.lose(p2, death = false))
        assertEquals(1, arena.wins[p1.uniqueId])
        assertEquals(0, env.store.readStats(p1.uniqueId).first)
        assertEquals(0, env.store.readStats(p2.uniqueId).second)
    }

    @Test
    fun `abort during pending respawn never reapplies kit`() {
        val arena = env.newArena()
        arena.kit = InventorySnapshot(items = listOf(env.item(Material.IRON_SWORD)))
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.service.join(p1, arena)
        env.service.join(p2, arena)
        env.tick(6)

        `when`(p2.isDead).thenReturn(true)
        env.service.lose(p2, death = true)
        env.service.abort(arena)
        env.runOneShots()
        assertEquals(Material.COMPASS, p2.inventory.contents[0]?.type)
    }

    @Test
    fun `final death restores original inventory after respawn`() {
        env.close()
        env = TestEnv(folder, requiredWins = 1)
        val arena = env.newArena()
        arena.kit = InventorySnapshot(items = listOf(env.item(Material.IRON_SWORD)))
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        p1.inventory.setItem(0, env.item(Material.BREAD))
        p2.inventory.setItem(0, env.item(Material.APPLE))
        env.service.join(p1, arena)
        env.service.join(p2, arena)
        env.tick(6)
        assertEquals(Material.IRON_SWORD, p2.inventory.contents[0]?.type)

        `when`(p2.isDead).thenReturn(true)
        env.service.lose(p2, death = true)
        assertEquals(ArenaState.WAITING, arena.state)
        env.runOneShots()
        val order = inOrder(p2.inventory, p2.spigot())
        order.verify(p2.spigot()).respawn()
        assertEquals(Material.APPLE, p2.inventory.contents[0]?.type)
        assertEquals(1, env.store.readStats(p1.uniqueId).first)
        assertEquals(1, env.store.readStats(p2.uniqueId).second)
    }

    @Test
    fun `quit during INGAME forfeits with stats and restores both`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        p1.inventory.setItem(0, env.item(Material.BREAD))
        env.service.join(p1, arena)
        env.service.join(p2, arena)
        env.tick(6)

        env.removePlayer(p2)
        env.service.quit(p2)

        assertEquals(ArenaState.WAITING, arena.state)
        assertNull(env.service.arenaOf(p1.uniqueId))
        assertNull(env.service.arenaOf(p2.uniqueId))
        assertEquals(1, env.store.readStats(p1.uniqueId).first)
        assertEquals(1, env.store.readStats(p2.uniqueId).second)
        assertEquals(Material.BREAD, p1.inventory.contents[0]?.type)
        assertTrue(lastBroadcast().contains("Alice"))
    }

    @Test
    fun `quit during COUNTDOWN forfeits`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.service.join(p1, arena)
        env.service.join(p2, arena)
        env.removePlayer(p1)
        env.service.quit(p1)
        assertEquals(ArenaState.WAITING, arena.state)
        assertEquals(1, env.store.readStats(p2.uniqueId).first)
        assertEquals(1, env.store.readStats(p1.uniqueId).second)
    }

    @Test
    fun `quit during ONEMORE unregisters without stats`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        p1.inventory.setItem(0, env.item(Material.BREAD))
        env.service.join(p1, arena)
        env.removePlayer(p1)
        env.service.quit(p1)
        assertEquals(ArenaState.WAITING, arena.state)
        assertNull(env.service.arenaOf(p1.uniqueId))
        assertFalse(env.store.statsExist(p1.uniqueId))
        assertEquals(Material.BREAD, p1.inventory.contents[0]?.type)
    }

    @Test
    fun `leave only allowed in ONEMORE`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.service.leave(p1)
        verify(p1).sendMessage(org.mockito.ArgumentMatchers.contains("あなたはアリーナに参加していません！"))

        env.service.join(p1, arena)
        env.service.join(p2, arena)
        env.service.leave(p1)
        verify(p1).sendMessage(org.mockito.ArgumentMatchers.contains("カウントダウン中はアリーナから退出できません！"))
        assertEquals(arena, env.service.arenaOf(p1.uniqueId))
    }

    @Test
    fun `leave in ONEMORE restores and resets arena`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        p1.inventory.setItem(0, env.item(Material.BREAD))
        env.service.join(p1, arena)
        env.service.leave(p1)
        verify(p1).sendMessage(org.mockito.ArgumentMatchers.contains("アリーナから退出しました"))
        assertEquals(ArenaState.WAITING, arena.state)
        assertNull(env.service.arenaOf(p1.uniqueId))
        assertEquals(Material.BREAD, p1.inventory.contents[0]?.type)
    }

    @Test
    fun `empty snapshot falls back to lobby items on restore`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        env.service.join(p1, arena)
        env.service.leave(p1)
        verify(p1.inventory).setItem(org.mockito.ArgumentMatchers.eq(0), org.mockito.ArgumentMatchers.argThat<ItemStack?> { it?.type == Material.COMPASS })
        verify(p1.inventory).setItem(org.mockito.ArgumentMatchers.eq(8), org.mockito.ArgumentMatchers.argThat<ItemStack?> { it?.type == Material.FEATHER })
    }

    @Test
    fun `empty kit does not grant lobby items`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.service.join(p1, arena)
        env.service.join(p2, arena)
        env.tick(6)
        assertNull(p1.inventory.contents[0])
        verify(p1.inventory, never()).setItem(org.mockito.ArgumentMatchers.eq(0), org.mockito.ArgumentMatchers.argThat<ItemStack?> { it?.type == Material.COMPASS })
    }

    @Test
    fun `shutdown preserves enabled and clears state`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        env.service.join(p1, arena)
        env.service.shutdown()
        assertTrue(arena.enabled)
        assertEquals(ArenaState.WAITING, arena.state)
        assertTrue(arena.players.isEmpty())
        assertNull(env.service.arenaOf(p1.uniqueId))
    }

    @Test
    fun `disable aborts and clears registration while persisting disabled`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.service.join(p1, arena)
        env.service.join(p2, arena)
        env.service.disableArena(arena)
        assertFalse(arena.enabled)
        assertEquals(ArenaState.WAITING, arena.state)
        assertNull(env.service.arenaOf(p1.uniqueId))
        assertNull(env.service.arenaOf(p2.uniqueId))
        env.tick(6)
        verify(p1, never()).teleport(any(Location::class.java))
        val reloaded = env.store.loadArena("arena1")!!
        assertFalse(reloaded.enabled)
    }

    @Test
    fun `enabled persists across service load`() {
        val arena = env.newArena(enabled = true)
        env.store.saveArena(arena)
        env.store.saveArenaNames(listOf("arena1"))
        env.service.load()
        assertTrue(env.service.arena("arena1")!!.enabled)
    }

    @Test
    fun `pending restore applied on join and discarded`() {
        val uuid = UUID.randomUUID()
        env.store.registerParticipant(Participant(uuid, "Alice", InventorySnapshot()), "a1")
        env.store.clearRegistrations()
        env.service.load()

        val p = env.player("Alice", uuid)
        p.inventory.setItem(0, env.item(Material.STONE))
        env.service.restorePending(p)
        org.mockito.Mockito.verify(p.inventory).setItem(org.mockito.ArgumentMatchers.eq(0), org.mockito.ArgumentMatchers.argThat<org.bukkit.inventory.ItemStack?> { it?.type == Material.COMPASS })
        val yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(File(folder, "status/players.yml"))
        assertNull(yaml.getConfigurationSection("inv.Alice"))
    }

    @Test
    fun `final death quit before respawn tick still restores original inventory`() {
        env.close()
        env = TestEnv(folder, requiredWins = 1)
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        p2.inventory.setItem(0, env.item(Material.APPLE))
        env.service.join(p1, arena)
        env.service.join(p2, arena)
        env.tick(6)

        `when`(p2.isDead).thenReturn(true)
        env.service.lose(p2, death = true)
        assertEquals(ArenaState.WAITING, arena.state)

        env.removePlayer(p2)
        env.service.quit(p2)
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
        env.service.join(p1, arena)
        env.service.join(p2, arena)
        env.tick(6)

        `when`(p2.isDead).thenReturn(true)
        env.service.lose(p2, death = true)
        env.service.shutdown()

        assertEquals(Material.APPLE, p2.inventory.contents[0]?.type)
        val yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(File(folder, "status/players.yml"))
        assertEquals(p2.uniqueId.toString(), yaml.getString("inv.Bob.uuid"))

        `when`(p2.isDead).thenReturn(false)
        env.service.restorePending(p2)
        assertEquals(Material.APPLE, p2.inventory.contents[0]?.type)
        val after = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(File(folder, "status/players.yml"))
        assertNull(after.getConfigurationSection("inv.Bob"))

        env.runOneShots()
        assertEquals(Material.APPLE, p2.inventory.contents[0]?.type)
    }

    @Test
    fun `abort dead player restores on next tick and stale callback cannot reapply kit`() {
        val arena = env.newArena()
        arena.kit = InventorySnapshot(items = listOf(env.item(Material.IRON_SWORD)))
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        p2.inventory.setItem(0, env.item(Material.APPLE))
        env.service.join(p1, arena)
        env.service.join(p2, arena)
        env.tick(6)

        `when`(p2.isDead).thenReturn(true)
        env.service.lose(p2, death = true)
        env.service.abort(arena)
        env.runOneShots()
        assertEquals(Material.APPLE, p2.inventory.contents[0]?.type)

        `when`(p2.isDead).thenReturn(false)
        env.service.join(p2, arena)
        assertEquals(arena, env.service.arenaOf(p2.uniqueId))
        assertEquals(Material.APPLE, p2.inventory.contents[0]?.type)
    }

    @Test
    fun `abort retains pending restore for offline participant`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        p2.inventory.setItem(0, env.item(Material.APPLE))
        env.service.join(p1, arena)
        env.service.join(p2, arena)
        env.tick(6)

        env.removePlayer(p2)
        env.service.abort(arena)

        val yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(File(folder, "status/players.yml"))
        assertEquals(p2.uniqueId.toString(), yaml.getString("inv.Bob.uuid"))

        `when`(p2.isOnline).thenReturn(true)
        env.players[p2.uniqueId] = p2
        env.service.restorePending(p2)
        assertEquals(Material.APPLE, p2.inventory.contents[0]?.type)
        val after = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(File(folder, "status/players.yml"))
        assertNull(after.getConfigurationSection("inv.Bob"))
    }

    @Test
    fun `name collision does not restore and renamed uuid does`() {
        val arena = env.newArena()
        val uuid = UUID.randomUUID()
        val original = env.player("Alice", uuid)
        original.inventory.setItem(0, env.item(Material.DIAMOND))
        env.service.join(original, arena)
        env.removePlayer(original)
        env.service.abort(arena)

        val squatter = env.player("Alice", UUID.randomUUID())
        squatter.inventory.setItem(0, env.item(Material.STONE))
        env.service.restorePending(squatter)
        assertEquals(Material.STONE, squatter.inventory.contents[0]?.type)

        val renamed = env.player("Alice2", uuid)
        env.service.restorePending(renamed)
        assertEquals(Material.DIAMOND, renamed.inventory.contents[0]?.type)
        val yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(File(folder, "status/players.yml"))
        assertNull(yaml.getConfigurationSection("inv.Alice"))
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
            env.service.join(p, arena)
        }
        assertNull(env.service.arenaOf(p.uniqueId))
        assertTrue(arena.players.isEmpty())
        assertEquals(ArenaState.WAITING, arena.state)
    }

    @Test
    fun `void loss in ROUNDCOUNTDOWN scores again after guard release and cancels old timer`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.service.join(p1, arena)
        env.service.join(p2, arena)
        env.tick(6)

        assertTrue(env.service.lose(p2, death = false))
        val roundTimerId = env.timers.last().taskId
        assertEquals(ArenaState.ROUNDCOUNTDOWN, arena.state)

        assertFalse(env.service.lose(p2, death = false))
        assertEquals(1, arena.wins[p1.uniqueId])

        env.runOneShots()
        assertTrue(env.service.lose(p2, death = false))
        assertEquals(2, arena.wins[p1.uniqueId])
        assertEquals(ArenaState.ROUNDCOUNTDOWN, arena.state)

        env.timers.first { it.taskId == roundTimerId }.runnable.run()
        assertTrue(env.cancelledTaskIds.contains(roundTimerId))
    }

    @Test
    fun `quit during ROUNDCOUNTDOWN forfeits with stats`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.service.join(p1, arena)
        env.service.join(p2, arena)
        env.tick(6)
        env.service.lose(p2, death = false)
        assertEquals(ArenaState.ROUNDCOUNTDOWN, arena.state)

        env.removePlayer(p1)
        env.service.quit(p1)
        assertEquals(ArenaState.WAITING, arena.state)
        assertEquals(1, env.store.readStats(p2.uniqueId).first)
        assertEquals(1, env.store.readStats(p1.uniqueId).second)
    }

    @Test
    fun `finish resets vitals and clears sidebars`() {
        env.close()
        env = TestEnv(folder, requiredWins = 1)
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.service.join(p1, arena)
        env.service.join(p2, arena)
        env.tick(6)

        env.service.lose(p2, death = false)
        verify(p1, org.mockito.Mockito.times(2)).setHealth(20.0)
        verify(p1, org.mockito.Mockito.times(2)).setFoodLevel(20)
        verify(p1, org.mockito.Mockito.times(2)).setFireTicks(0)
        verify(p2, org.mockito.Mockito.times(2)).setHealth(20.0)
        verify(p2, org.mockito.Mockito.times(2)).setScoreboard(org.mockito.ArgumentMatchers.any())
        verify(p1, org.mockito.Mockito.times(2)).setScoreboard(org.mockito.ArgumentMatchers.any())
    }

    @Test
    fun `abort clears sidebar`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        val p2 = env.player("Bob")
        env.service.join(p1, arena)
        env.service.join(p2, arena)
        env.service.abort(arena)
        verify(p1).setScoreboard(org.mockito.ArgumentMatchers.any())
        verify(p2).setScoreboard(org.mockito.ArgumentMatchers.any())
    }

    @Test
    fun `createArena rejects case insensitive duplicates`() {
        assertTrue(env.service.createArena("Arena1"))
        assertFalse(env.service.createArena("arena1"))
        assertFalse(env.service.createArena("PLAYERS"))
    }
}
