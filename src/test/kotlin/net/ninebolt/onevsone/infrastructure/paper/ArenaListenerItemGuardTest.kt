package net.ninebolt.onevsone.infrastructure.paper

import net.kyori.adventure.text.Component
import net.ninebolt.onevsone.infrastructure.paper.fixtures.TestEnv
import net.ninebolt.onevsone.infrastructure.paper.fixtures.blockOf
import net.ninebolt.onevsone.infrastructure.paper.fixtures.interact
import net.ninebolt.onevsone.infrastructure.paper.fixtures.itemEntity
import net.ninebolt.onevsone.infrastructure.paper.fixtures.plainBlock
import net.ninebolt.onevsone.infrastructure.paper.fixtures.spawn
import net.ninebolt.onevsone.infrastructure.paper.fixtures.twoPlayerIngame
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.block.sign.Side
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.EntityType
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.block.BlockDispenseArmorEvent
import org.bukkit.event.block.BlockFertilizeEvent
import org.bukkit.event.block.SignChangeEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.EntityPlaceEvent
import org.bukkit.event.hanging.HangingPlaceEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerArmorStandManipulateEvent
import org.bukkit.event.player.PlayerAttemptPickupItemEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerBucketEntityEvent
import org.bukkit.event.player.PlayerBucketFillEvent
import org.bukkit.event.player.PlayerHarvestBlockEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerPickupArrowEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** キット品の外界移動・直接獲得の遮断。実イベントの isCancelled / useInteractedBlock を見る。 */
class ArenaListenerItemGuardTest {

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
    fun `container interact denied only while restricted`() {
        val (p1, _) = env.twoPlayerIngame()
        val chest = env.blockOf(Material.CHEST)

        val ingame = interact(p1, chest)
        env.listener.onInteract(ingame)
        assertEquals(Event.Result.DENY, ingame.useInteractedBlock())
        assertEquals(Event.Result.DENY, ingame.useItemInHand())
    }

    @Test
    fun `container interact allowed while onemore`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        env.join(p1, arena)
        val chest = env.blockOf(Material.CHEST)

        val event = interact(p1, chest)
        env.listener.onInteract(event)
        assertNotEquals(Event.Result.DENY, event.useInteractedBlock())
    }

    @Test
    fun `ender chest and spawn setting blocks denied`() {
        val (p1, _) = env.twoPlayerIngame()
        // InventoryHolder を持たない預け入れ経路を個別に塞ぐ
        listOf(
            Material.ENDER_CHEST,
            Material.WHITE_BED,
            Material.RESPAWN_ANCHOR,
            Material.FLOWER_POT,
            Material.POTTED_OAK_SAPLING
        ).forEachIndexed { i, type ->
            val event = interact(p1, env.blockOf(type, x = 8, z = 20 + i))
            env.listener.onInteract(event)
            assertEquals(Event.Result.DENY, event.useInteractedBlock(), "type=$type")
        }
    }

    @Test
    fun `plain block interact and non participant unaffected`() {
        val (p1, _) = env.twoPlayerIngame()
        val plain = interact(p1, env.plainBlock())
        env.listener.onInteract(plain)
        assertNotEquals(Event.Result.DENY, plain.useInteractedBlock())

        val outsider = env.player("Outsider")
        val foreign = interact(outsider, env.blockOf(Material.CHEST))
        env.listener.onInteract(foreign)
        assertNotEquals(Event.Result.DENY, foreign.useInteractedBlock())
    }

    @Test
    fun `spawn egg use denied while restricted`() {
        val (p1, _) = env.twoPlayerIngame()
        val egg = interact(p1, env.plainBlock(), item = env.item(Material.ZOMBIE_SPAWN_EGG))
        env.listener.onInteract(egg)
        assertEquals(Event.Result.DENY, egg.useItemInHand())
    }

    @Test
    fun `foreign inventory clicks cancelled but own inventory allowed`() {
        val (p1, _) = env.twoPlayerIngame()

        val own = InventoryClickEvent(
            p1.openInventory, InventoryType.SlotType.OUTSIDE, 0,
            ClickType.LEFT, InventoryAction.PICKUP_ALL
        )
        env.listener.onInventoryClick(own)
        assertFalse(own.isCancelled)

        val chestView = p1.openInventory(env.server.createInventory(null, InventoryType.CHEST))!!
        val foreign = InventoryClickEvent(
            chestView, InventoryType.SlotType.CONTAINER, 0,
            ClickType.LEFT, InventoryAction.PICKUP_ALL
        )
        env.listener.onInventoryClick(foreign)
        assertTrue(foreign.isCancelled)

        val drag = InventoryDragEvent(
            chestView, null, env.item(Material.STONE), false,
            mapOf(0 to env.item(Material.STONE))
        )
        env.listener.onInventoryDrag(drag)
        assertTrue(drag.isCancelled)
    }

    @Test
    fun `entity interact cancelled for item keeping entities`() {
        val (p1, _) = env.twoPlayerIngame()

        listOf(
            EntityType.ITEM_FRAME,
            EntityType.ARMOR_STAND,
            EntityType.CHEST_MINECART
        ).forEach { type ->
            val entity = env.spawn(type)
            val event = PlayerInteractEntityEvent(p1, entity)
            env.listener.onInteractEntity(event)
            assertTrue(event.isCancelled, "type=$type")
        }

        val pig = PlayerInteractEntityEvent(p1, env.spawn(EntityType.PIG))
        env.listener.onInteractEntity(pig)
        assertFalse(pig.isCancelled)
    }

    @Test
    fun `armor stand manipulate needs own handler`() {
        val (p1, _) = env.twoPlayerIngame()
        val stand = env.spawn(EntityType.ARMOR_STAND) as ArmorStand
        val event = PlayerArmorStandManipulateEvent(
            p1, stand, env.item(Material.IRON_CHESTPLATE), ItemStack.empty(),
            EquipmentSlot.HAND, EquipmentSlot.HAND
        )
        env.listener.onArmorStandManipulate(event)
        assertTrue(event.isCancelled)
    }

    @Test
    fun `entity place hanging place and buckets cancelled while restricted`() {
        val (p1, _) = env.twoPlayerIngame()
        val block = env.plainBlock()

        val place = EntityPlaceEvent(env.spawn(EntityType.BOAT), p1, block, BlockFace.UP, EquipmentSlot.HAND)
        env.listener.onEntityPlace(place)
        assertTrue(place.isCancelled)

        val hanging = HangingPlaceEvent(
            env.spawn(EntityType.ITEM_FRAME) as ItemFrame, p1, block,
            BlockFace.EAST, EquipmentSlot.HAND, env.item(Material.ITEM_FRAME)
        )
        env.listener.onHangingPlace(hanging)
        assertTrue(hanging.isCancelled)

        val bucketEmpty = PlayerBucketEmptyEvent(
            p1, block, block, BlockFace.UP, Material.WATER_BUCKET,
            env.item(Material.WATER_BUCKET), EquipmentSlot.HAND
        )
        env.listener.onBucketEmpty(bucketEmpty)
        assertTrue(bucketEmpty.isCancelled)

        val bucketFill = PlayerBucketFillEvent(
            p1, block, block, BlockFace.UP, Material.BUCKET,
            env.item(Material.BUCKET), EquipmentSlot.HAND
        )
        env.listener.onBucketFill(bucketFill)
        assertTrue(bucketFill.isCancelled)

        val bucketEntity = PlayerBucketEntityEvent(
            p1, env.spawn(EntityType.COD), env.item(Material.WATER_BUCKET),
            env.item(Material.COD_BUCKET), EquipmentSlot.HAND
        )
        env.listener.onBucketEntity(bucketEntity)
        assertTrue(bucketEntity.isCancelled)
    }

    @Test
    fun `pickup harvest and dispensed armor cancelled while restricted`() {
        val (p1, _) = env.twoPlayerIngame()
        val item = env.itemEntity()

        val pickup = EntityPickupItemEvent(p1, item, 0)
        env.listener.onEntityPickupItem(pickup)
        assertTrue(pickup.isCancelled)

        val attempt = PlayerAttemptPickupItemEvent(p1, env.itemEntity(), 0)
        env.listener.onAttemptPickupItem(attempt)
        assertTrue(attempt.isCancelled)

        val arrowPickup = PlayerPickupArrowEvent(
            p1, env.itemEntity(), env.spawn(EntityType.ARROW) as AbstractArrow
        )
        env.listener.onPickupArrow(arrowPickup)
        assertTrue(arrowPickup.isCancelled)

        val harvest = PlayerHarvestBlockEvent(
            p1, env.plainBlock(), EquipmentSlot.HAND, mutableListOf(env.item(Material.SWEET_BERRIES))
        )
        env.listener.onHarvest(harvest)
        assertTrue(harvest.isCancelled)

        val dispense = BlockDispenseArmorEvent(env.plainBlock(), env.item(Material.IRON_HELMET), p1)
        env.listener.onDispenseArmor(dispense)
        assertTrue(dispense.isCancelled)
    }

    @Test
    fun `pickup allowed while onemore`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        env.join(p1, arena)
        val pickup = EntityPickupItemEvent(p1, env.itemEntity(), 0)
        env.listener.onEntityPickupItem(pickup)
        assertFalse(pickup.isCancelled)
    }

    @Test
    fun `fertilize and sign change cancelled while restricted`() {
        val (p1, _) = env.twoPlayerIngame()

        val fertilize = BlockFertilizeEvent(env.plainBlock(), p1, listOf(env.plainBlock().state))
        env.listener.onFertilize(fertilize)
        assertTrue(fertilize.isCancelled)

        val sign = SignChangeEvent(env.blockOf(Material.OAK_SIGN), p1, listOf(Component.text("x")), Side.FRONT)
        env.listener.onSignChange(sign)
        assertTrue(sign.isCancelled)
    }
}
