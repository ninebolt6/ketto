package net.ninebolt.onevsone.infrastructure.paper

import net.ninebolt.onevsone.domain.ArenaState
import net.ninebolt.onevsone.domain.DefeatCause
import net.ninebolt.onevsone.infrastructure.paper.fixtures.TestEnv
import net.ninebolt.onevsone.infrastructure.paper.fixtures.assertFired
import net.ninebolt.onevsone.infrastructure.paper.fixtures.attackDamage
import net.ninebolt.onevsone.infrastructure.paper.fixtures.genericDamage
import net.ninebolt.onevsone.infrastructure.paper.fixtures.mob
import net.ninebolt.onevsone.infrastructure.paper.fixtures.projectileDamage
import net.ninebolt.onevsone.infrastructure.paper.fixtures.simulation
import net.ninebolt.onevsone.infrastructure.paper.fixtures.spawn
import net.ninebolt.onevsone.infrastructure.paper.fixtures.twoPlayerIngame
import net.ninebolt.onevsone.infrastructure.paper.fixtures.uuid
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Biome
import org.bukkit.damage.DamageType
import org.bukkit.entity.EntityType
import org.bukkit.event.entity.PlayerDeathEvent
import org.mockbukkit.mockbukkit.world.WorldMock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** 死亡・ダメージ・切断・移動を実アクション(simulateDamage/disconnect/移動シミュレーション)経由で検証する。 */
class ArenaListenerCombatTest {

    @TempDir
    lateinit var folder: File

    private lateinit var env: TestEnv

    @BeforeEach
    fun setup() {
        env = TestEnv(folder)
        env.registerListeners()
    }

    @AfterEach
    fun tearDown() {
        env.close()
    }

    @Test
    fun `death event keeps inventory clears drops and resolves round`() {
        val (p1, p2) = env.twoPlayerIngame()
        p2.simulateDamage(100.0, genericDamage())
        env.assertFired<PlayerDeathEvent> { event ->
            event.keepInventory && event.drops.isEmpty() && event.droppedExp == 0 && event.keepLevel
        }
        assertEquals(ArenaState.ROUNDCOUNTDOWN, env.state())
        assertEquals(1, env.service.matchOf("arena1")!!.winsOf(p1.uuid))
    }

    @Test
    fun `non participant death ignored`() {
        val outsider = env.player("Outsider")
        outsider.simulateDamage(100.0, genericDamage())
        env.assertFired<PlayerDeathEvent> { event -> !event.keepInventory && !event.keepLevel }
    }

    @Test
    fun `participant death while waiting respawns without resolving match`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        env.join(p1, arena)
        assertEquals(ArenaState.ONEMORE, env.state())

        p1.simulateDamage(100.0, genericDamage())
        // 試合未開始の参加者死亡は敗北にせず、次 tick でリスポーンさせる
        // (keepInventory の実効は MockBukkit がエミュレートしないためフラグ設定までを検証)
        env.assertFired<PlayerDeathEvent> { event -> event.keepInventory && event.keepLevel }
        assertEquals(ArenaState.ONEMORE, env.state())
        assertEquals(arena, env.service.arenaIdOf(p1.uuid))

        env.runOneShots()
        assertEquals(1, p1.respawnCount)
        assertNull(env.statsRepo.find(p1.uuid))
    }

    @Test
    fun `non player damage ignored`() {
        val event = env.mob().simulateDamage(1.0, genericDamage())
        assertFalse(event.isCancelled)
    }

    @Test
    fun `damage not cancelled in INGAME`() {
        val (p1, _) = env.twoPlayerIngame()
        val event = p1.simulateDamage(1.0, genericDamage())
        assertFalse(event.isCancelled)
    }

    @Test
    fun `damage cancelled in round countdown state`() {
        val (p1, p2) = env.twoPlayerIngame()
        env.service.defeat(p2.uuid, DefeatCause.FALL)
        assertEquals(ArenaState.ROUNDCOUNTDOWN, env.state())
        val event = p1.simulateDamage(1.0, genericDamage())
        assertTrue(event.isCancelled)
        assertEquals(20.0, p1.health)
    }

    @Test
    fun `opponent entity damage allowed in INGAME`() {
        val (p1, p2) = env.twoPlayerIngame()
        val event = p1.simulateDamage(1.0, attackDamage(p2))
        assertFalse(event.isCancelled)
    }

    @Test
    fun `third party and mob damage cancelled in INGAME`() {
        val (p1, _) = env.twoPlayerIngame()
        val outsider = env.player("Outsider")

        val sniped = p1.simulateDamage(1.0, attackDamage(outsider))
        assertTrue(sniped.isCancelled)

        val mobbed = p1.simulateDamage(1.0, attackDamage(env.mob(), DamageType.MOB_ATTACK))
        assertTrue(mobbed.isCancelled)
        assertEquals(20.0, p1.health)
    }

    @Test
    fun `opponent projectile damage attributed via causing entity`() {
        val (p1, p2) = env.twoPlayerIngame()
        val arrow = env.spawn(EntityType.ARROW)
        // 直接の damager は矢でも、causingEntity が対戦相手なら許可
        val allowed = p1.simulateDamage(1.0, projectileDamage(arrow, p2))
        assertFalse(allowed.isCancelled)

        val smuggled = p1.simulateDamage(1.0, projectileDamage(arrow, env.player("Outsider")))
        assertTrue(smuggled.isCancelled)
    }

    @Test
    fun `participant cannot damage outsiders or mobs`() {
        val (p1, _) = env.twoPlayerIngame()
        val outsider = env.player("Outsider")

        val hitPlayer = outsider.simulateDamage(1.0, attackDamage(p1))
        assertTrue(hitPlayer.isCancelled)

        val hitMob = env.mob().simulateDamage(1.0, attackDamage(p1))
        assertTrue(hitMob.isCancelled)
    }

    @Test
    fun `opponent damage cancelled during round countdown`() {
        val (p1, p2) = env.twoPlayerIngame()
        env.service.defeat(p2.uuid, DefeatCause.FALL)
        val event = p1.simulateDamage(1.0, attackDamage(p2))
        assertTrue(event.isCancelled)
    }

    @Test
    fun `outsiders fighting each other unaffected`() {
        env.twoPlayerIngame()
        val a = env.player("OutsiderA")
        val b = env.player("OutsiderB")
        val event = a.simulateDamage(1.0, attackDamage(b))
        assertFalse(event.isCancelled)
    }

    @Test
    fun `quit of outsider does not touch inventory`() {
        val outsider = env.player("Outsider")
        outsider.inventory.setItem(0, env.item(Material.STONE))
        outsider.disconnect()
        assertEquals(Material.STONE, outsider.inventory.contents[0]?.type)
    }

    @Test
    fun `quit of participant resolves through quitting scope`() {
        val (p1, p2) = env.twoPlayerIngame()
        p1.inventory.setItem(0, null)
        p1.disconnect()
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
        val sim = p1.simulation()
        sim.simulatePlayerMove(Location(w, 0.0, -5.0, 0.0))
        assertEquals(ArenaState.ONEMORE, env.state())

        val p2 = env.player("Bob")
        env.join(p2, arena)
        env.tick(6)

        sim.simulatePlayerMove(Location(w, 0.0, -1.0, 0.0))
        assertEquals(ArenaState.ROUNDCOUNTDOWN, env.state())
        assertEquals(1, env.service.matchOf("arena1")!!.winsOf(p2.uuid))
    }

    @Test
    fun `round countdown freezes xz movement but allows y only`() {
        val (p1, p2) = env.twoPlayerIngame()
        env.service.defeat(p2.uuid, DefeatCause.FALL)

        val sim = p1.simulation()
        val from = p1.location
        val horizontal = sim.simulatePlayerMove(from.clone().add(1.0, 0.0, 0.0))
        // 凍結は setTo(from) の書き換えで通知する。simulatePlayerMove はキャンセル時のみ
        // 実位置を戻すため実位置は移動先のまま残り、イベントの to で判定結果を見る
        assertEquals(from, horizontal.to)

        val verticalTarget = p1.location.clone().add(0.0, 1.0, 0.0)
        val vertical = sim.simulatePlayerMove(verticalTarget)
        assertEquals(verticalTarget, vertical.to)
    }

    @Test
    fun `void fall uses world min height`() {
        // 最低高度が負の世界では y<0 の移動は敗北にしない
        // WorldMock は (minHeight, maxHeight, grassHeight) の順
        val deep = WorldMock(Material.STONE, Biome.PLAINS, -64, 320, 0)
        env.server.addWorld(deep)
        val (p1, p2) = env.twoPlayerIngame()
        val sim = p1.simulation()

        sim.simulatePlayerMove(Location(deep, 0.0, -55.0, 0.0))
        assertEquals(ArenaState.INGAME, env.state())

        sim.simulatePlayerMove(Location(deep, 0.0, -65.0, 0.0))
        assertEquals(ArenaState.ROUNDCOUNTDOWN, env.state())
        assertEquals(1, env.service.matchOf("arena1")!!.winsOf(p2.uuid))
    }

    @Test
    fun `void fall in ROUNDCOUNTDOWN scores again after resolving guard released`() {
        val (p1, p2) = env.twoPlayerIngame()
        env.service.defeat(p2.uuid, DefeatCause.FALL)
        assertEquals(ArenaState.ROUNDCOUNTDOWN, env.state())

        env.runOneShots()
        val sim = p2.simulation()
        // ROUNDCOUNTDOWN は水平移動が凍結されるため、現在地の xz を保って y だけ落とす
        val base = p2.location
        sim.simulatePlayerMove(Location(base.world, base.x, -1.0, base.z))
        assertEquals(2, env.service.matchOf("arena1")!!.winsOf(p1.uuid))
        // キャンセル済みの旧タイマーは実スケジューラ上は二度と発火せず、新タイマーだけが進行する
        env.tick(8)
        assertEquals(ArenaState.INGAME, env.state())

        sim.simulatePlayerMove(Location(base.world, base.x, -1.0, base.z))
        assertEquals(ArenaState.WAITING, env.state())
    }
}
