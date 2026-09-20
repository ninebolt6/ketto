package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.DefeatCause
import net.ninebolt.onevsone.infrastructure.paper.fixtures.TestEnv
import net.ninebolt.onevsone.infrastructure.paper.fixtures.damageEvent
import net.ninebolt.onevsone.infrastructure.paper.fixtures.deathEvent
import net.ninebolt.onevsone.infrastructure.paper.fixtures.moveEvent
import net.ninebolt.onevsone.infrastructure.paper.fixtures.nonPlayer
import net.ninebolt.onevsone.infrastructure.paper.fixtures.quitEvent
import net.ninebolt.onevsone.infrastructure.paper.fixtures.twoPlayerIngame
import net.ninebolt.onevsone.infrastructure.paper.fixtures.uuid
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.event.player.PlayerTeleportEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** 死亡・ダメージ・切断・移動イベントのハンドリング。実イベントオブジェクトで検証する。 */
class ArenaListenerCombatTest {

    @TempDir
    lateinit var folder: File

    private lateinit var env: TestEnv

    @BeforeEach
    fun setup() {
        env = TestEnv(folder)
    }

    @AfterEach
    fun tearDown() {
        env.close()
    }

    @Test
    fun `death event keeps inventory clears drops and resolves round`() {
        val (p1, p2) = env.twoPlayerIngame()
        p2.health = 0.0
        val event = env.deathEvent(p2, droppedExp = 30)
        env.listener.onDeath(event)
        assertTrue(event.keepInventory)
        assertTrue(event.drops.isEmpty())
        // 経験値はドロップさせず、リスポーン後もレベル・経験値を保持する
        assertEquals(0, event.droppedExp)
        assertTrue(event.keepLevel)
        assertEquals(ArenaState.ROUNDCOUNTDOWN, env.state())
        assertEquals(1, env.service.matchOf("arena1")!!.winsOf(p1.uuid))
    }

    @Test
    fun `non participant death ignored`() {
        val outsider = env.player("Outsider")
        val event = env.deathEvent(outsider, droppedExp = 30)
        env.listener.onDeath(event)
        assertFalse(event.keepInventory)
        assertEquals(30, event.droppedExp)
        assertFalse(event.keepLevel)
    }

    @Test
    fun `non player damage ignored`() {
        val event = damageEvent(env.nonPlayer())
        env.listener.onDamage(event)
        assertFalse(event.isCancelled)
    }

    @Test
    fun `damage not cancelled in INGAME`() {
        val (p1, _) = env.twoPlayerIngame()
        val event = damageEvent(p1)
        env.listener.onDamage(event)
        assertFalse(event.isCancelled)
    }

    @Test
    fun `damage cancelled in round countdown state`() {
        val (p1, p2) = env.twoPlayerIngame()
        env.service.defeat(p2.uuid, DefeatCause.FALL)
        assertEquals(ArenaState.ROUNDCOUNTDOWN, env.state())
        val event = damageEvent(p1)
        env.listener.onDamage(event)
        assertTrue(event.isCancelled)
    }

    @Test
    fun `quit of outsider does not touch inventory`() {
        val outsider = env.player("Outsider")
        outsider.inventory.setItem(0, env.item(Material.STONE))
        env.listener.onQuit(quitEvent(outsider))
        assertEquals(Material.STONE, outsider.inventory.contents[0]?.type)
    }

    @Test
    fun `quit of participant resolves through quitting scope`() {
        val (p1, p2) = env.twoPlayerIngame()
        p1.inventory.setItem(0, null)
        env.removePlayer(p1)
        env.listener.onQuit(quitEvent(p1))
        assertEquals(ArenaState.WAITING, env.state())
        assertEquals(1, env.statsRepo.find(p2.uuid)!!.wins)
    }

    @Test
    fun `void fall below zero resolves only when ingame with two players`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        env.join(p1, arena)
        assertEquals(ArenaState.ONEMORE, env.state())

        val w = env.world()
        val event = moveEvent(p1, Location(w, 0.0, -1.0, 0.0), Location(w, 0.0, -5.0, 0.0))
        env.listener.onMove(event)
        assertEquals(ArenaState.ONEMORE, env.state())

        val p2 = env.player("Bob")
        env.join(p2, arena)
        env.tick(6)

        val fall = moveEvent(p1, Location(w, 0.0, 1.0, 0.0), Location(w, 0.0, -1.0, 0.0))
        env.listener.onMove(fall)
        assertEquals(ArenaState.ROUNDCOUNTDOWN, env.state())
        assertEquals(1, env.service.matchOf("arena1")!!.winsOf(p2.uuid))
    }

    @Test
    fun `round countdown freezes xz movement but allows y only`() {
        val (p1, p2) = env.twoPlayerIngame()
        env.service.defeat(p2.uuid, DefeatCause.FALL)

        val w = env.world()
        val from = Location(w, 0.0, 64.0, 0.0)
        val horizontal = moveEvent(p1, from, Location(w, 1.0, 64.0, 0.0))
        env.listener.onMove(horizontal)
        assertEquals(from, horizontal.to)

        val verticalTo = Location(w, 0.0, 65.0, 0.0)
        val vertical = moveEvent(p1, Location(w, 0.0, 64.0, 0.0), verticalTo)
        env.listener.onMove(vertical)
        assertEquals(verticalTo, vertical.to)
    }

    @Test
    fun `teleport events are excluded from move handling`() {
        val (p1, p2) = env.twoPlayerIngame()
        env.service.defeat(p2.uuid, DefeatCause.FALL)
        val w = env.world()
        val to = Location(w, 5.0, -3.0, 0.0)
        val event = PlayerTeleportEvent(p1, Location(w, 0.0, 64.0, 0.0), to)
        env.listener.onMove(event)
        assertEquals(to, event.to)
    }

    @Test
    fun `void fall in ROUNDCOUNTDOWN scores again after resolving guard released`() {
        val (p1, p2) = env.twoPlayerIngame()
        env.service.defeat(p2.uuid, DefeatCause.FALL)
        assertEquals(ArenaState.ROUNDCOUNTDOWN, env.state())

        env.runOneShots()
        val w = env.world()
        env.listener.onMove(moveEvent(p2, Location(w, 0.0, 1.0, 0.0), Location(w, 0.0, -1.0, 0.0)))
        assertEquals(2, env.service.matchOf("arena1")!!.winsOf(p1.uuid))
        // キャンセル済みの旧タイマーは実スケジューラ上は二度と発火せず、新タイマーだけが進行する
        env.tick(8)
        assertEquals(ArenaState.INGAME, env.state())

        env.listener.onMove(moveEvent(p2, Location(w, 0.0, 1.0, 0.0), Location(w, 0.0, -1.0, 0.0)))
        assertEquals(ArenaState.WAITING, env.state())
    }
}
