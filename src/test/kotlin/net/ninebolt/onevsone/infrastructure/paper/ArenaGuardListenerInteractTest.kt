package net.ninebolt.onevsone.infrastructure.paper

import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.Component
import net.ninebolt.onevsone.infrastructure.paper.fixtures.TestEnv
import net.ninebolt.onevsone.infrastructure.paper.fixtures.blockOf
import net.ninebolt.onevsone.infrastructure.paper.fixtures.interact
import net.ninebolt.onevsone.infrastructure.paper.fixtures.itemEntity
import net.ninebolt.onevsone.infrastructure.paper.fixtures.nonPlayer
import net.ninebolt.onevsone.infrastructure.paper.fixtures.placeBlock
import net.ninebolt.onevsone.infrastructure.paper.fixtures.plainBlock
import net.ninebolt.onevsone.infrastructure.paper.fixtures.simulation
import net.ninebolt.onevsone.infrastructure.paper.fixtures.spawn
import net.ninebolt.onevsone.infrastructure.paper.fixtures.twoPlayerIngame
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.block.sign.Side
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.EntityType
import org.bukkit.entity.Fish
import org.bukkit.entity.HumanEntity
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.block.Action
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
import org.bukkit.event.player.PlayerBucketFishEvent
import org.bukkit.event.player.PlayerHarvestBlockEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerPickupArrowEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Vector
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockbukkit.mockbukkit.inventory.SimpleInventoryViewMock
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ArenaGuardListenerInteractTest {

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
        env.fire(ingame)
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
        env.fire(event)
        assertNotEquals(Event.Result.DENY, event.useInteractedBlock())
    }

    @Test
    fun `ender chest and spawn setting blocks denied`() {
        val (p1, _) = env.twoPlayerIngame()
        listOf(
            Material.ENDER_CHEST,
            Material.WHITE_BED,
            Material.RESPAWN_ANCHOR,
            Material.FLOWER_POT,
            Material.POTTED_OAK_SAPLING,
        ).forEachIndexed { i, type ->
            val event = interact(p1, env.blockOf(type, x = 8, z = 20 + i))
            env.fire(event)
            assertEquals(Event.Result.DENY, event.useInteractedBlock(), "type=$type")
        }
    }

    @Test
    fun `plain block interact and non participant unaffected`() {
        val (p1, _) = env.twoPlayerIngame()
        val plain = interact(p1, env.plainBlock())
        env.fire(plain)
        assertNotEquals(Event.Result.DENY, plain.useInteractedBlock())

        val outsider = env.player("Outsider")
        val foreign = interact(outsider, env.blockOf(Material.CHEST))
        env.fire(foreign)
        assertNotEquals(Event.Result.DENY, foreign.useInteractedBlock())
    }

    @Test
    fun `spawn egg use denied while restricted`() {
        val (p1, _) = env.twoPlayerIngame()
        val egg = interact(p1, env.plainBlock(), item = env.item(Material.ZOMBIE_SPAWN_EGG))
        env.fire(egg)
        assertEquals(Event.Result.DENY, egg.useItemInHand())
    }

    // InventoryClickEvent resolves raw slots through InventoryView.convertSlot, which SimpleInventoryViewMock leaves unimplemented
    private fun inventoryView(player: Player, top: Inventory): SimpleInventoryViewMock = object : SimpleInventoryViewMock() {
        override fun convertSlot(rawSlot: Int): Int = rawSlot
    }.apply {
        this.player = player
        topInventory = top
    }

    private fun click(view: SimpleInventoryViewMock) = InventoryClickEvent(
        view,
        InventoryType.SlotType.CONTAINER,
        0,
        ClickType.LEFT,
        InventoryAction.PICKUP_ALL,
    )

    private fun drag(view: SimpleInventoryViewMock) = InventoryDragEvent(view, null, env.item(Material.STONE), false, emptyMap())

    @Test
    fun `foreign inventory clicks and drags cancelled but own inventory allowed`() {
        val (p1, _) = env.twoPlayerIngame()

        val foreign = inventoryView(p1, env.server.createInventory(null, InventoryType.CHEST))
        val click = click(foreign)
        env.fire(click)
        assertTrue(click.isCancelled)

        val foreignDrag = drag(foreign)
        env.fire(foreignDrag)
        assertTrue(foreignDrag.isCancelled)

        val own = click(inventoryView(p1, p1.inventory))
        env.fire(own)
        assertFalse(own.isCancelled)
    }

    @Test
    fun `foreign inventory transfer allowed for outsiders and while onemore`() {
        env.twoPlayerIngame()
        val solo = env.newArena("solo")
        val waiting = env.player("Carol")
        env.join(waiting, solo)

        listOf(waiting, env.player("Outsider")).forEach { p ->
            val foreignClick = click(inventoryView(p, env.server.createInventory(null, InventoryType.CHEST)))
            env.fire(foreignClick)
            assertFalse(foreignClick.isCancelled)
            val foreignDrag = drag(inventoryView(p, env.server.createInventory(null, InventoryType.CHEST)))
            env.fire(foreignDrag)
            assertFalse(foreignDrag.isCancelled)
        }
    }

    @Test
    fun `entity interact cancelled for item keeping entities`() {
        val (p1, _) = env.twoPlayerIngame()

        listOf(
            EntityType.ITEM_FRAME,
            EntityType.ARMOR_STAND,
            EntityType.CHEST_MINECART,
        ).forEach { type ->
            val entity = env.spawn(type)
            val event = PlayerInteractEntityEvent(p1, entity)
            env.fire(event)
            assertTrue(event.isCancelled, "type=$type")
        }

        val pig = PlayerInteractEntityEvent(p1, env.spawn(EntityType.PIG))
        env.fire(pig)
        assertFalse(pig.isCancelled)
    }

    @Test
    fun `precise entity interact needs own handler`() {
        // PlayerInteractAtEntityEvent has its own HandlerList, so firing PlayerInteractEntityEvent never reaches it
        val (p1, _) = env.twoPlayerIngame()
        val frame = env.spawn(EntityType.ITEM_FRAME)
        val event = PlayerInteractAtEntityEvent(
            p1,
            frame,
            Vector(0.5, 1.0, 0.5),
            EquipmentSlot.HAND,
        )
        env.fire(event)
        assertTrue(event.isCancelled)
    }

    @Test
    fun `armor stand manipulate needs own handler`() {
        val (p1, _) = env.twoPlayerIngame()
        val stand = env.spawn(EntityType.ARMOR_STAND) as ArmorStand
        val event = PlayerArmorStandManipulateEvent(
            p1,
            stand,
            env.item(Material.IRON_CHESTPLATE),
            ItemStack.empty(),
            EquipmentSlot.HAND,
            EquipmentSlot.HAND,
        )
        env.fire(event)
        assertTrue(event.isCancelled)
    }

    @Test
    fun `entity place hanging place and buckets cancelled while restricted`() {
        val (p1, _) = env.twoPlayerIngame()
        val block = env.plainBlock()

        val place = EntityPlaceEvent(env.spawn(EntityType.OAK_BOAT), p1, block, BlockFace.UP, EquipmentSlot.HAND)
        env.fire(place)
        assertTrue(place.isCancelled)

        val hanging = HangingPlaceEvent(
            env.spawn(EntityType.ITEM_FRAME) as ItemFrame,
            p1,
            block,
            BlockFace.EAST,
            EquipmentSlot.HAND,
            env.item(Material.ITEM_FRAME),
        )
        env.fire(hanging)
        assertTrue(hanging.isCancelled)

        val bucketEmpty = PlayerBucketEmptyEvent(
            p1,
            block,
            block,
            BlockFace.UP,
            Material.WATER_BUCKET,
            env.item(Material.WATER_BUCKET),
            EquipmentSlot.HAND,
        )
        env.fire(bucketEmpty)
        assertTrue(bucketEmpty.isCancelled)

        val bucketFill = PlayerBucketFillEvent(
            p1,
            block,
            block,
            BlockFace.UP,
            Material.BUCKET,
            env.item(Material.BUCKET),
            EquipmentSlot.HAND,
        )
        env.fire(bucketFill)
        assertTrue(bucketFill.isCancelled)

        val bucketEntity = PlayerBucketEntityEvent(
            p1,
            env.spawn(EntityType.COD),
            env.item(Material.WATER_BUCKET),
            env.item(Material.COD_BUCKET),
            EquipmentSlot.HAND,
        )
        env.fire(bucketEntity)
        assertTrue(bucketEntity.isCancelled)
    }

    @Test
    fun `pickup harvest and dispensed armor cancelled while restricted`() {
        val (p1, _) = env.twoPlayerIngame()
        val item = env.itemEntity()

        val pickup = EntityPickupItemEvent(p1, item, 0)
        env.fire(pickup)
        assertTrue(pickup.isCancelled)

        val attempt = PlayerAttemptPickupItemEvent(p1, env.itemEntity(), 0)
        env.fire(attempt)
        assertTrue(attempt.isCancelled)

        val arrowPickup = PlayerPickupArrowEvent(
            p1,
            env.itemEntity(),
            env.spawn(EntityType.ARROW) as AbstractArrow,
        )
        env.fire(arrowPickup)
        assertTrue(arrowPickup.isCancelled)

        val harvest = PlayerHarvestBlockEvent(
            p1,
            env.plainBlock(),
            EquipmentSlot.HAND,
            mutableListOf(env.item(Material.SWEET_BERRIES)),
        )
        env.fire(harvest)
        assertTrue(harvest.isCancelled)

        val dispense = BlockDispenseArmorEvent(env.plainBlock(), env.item(Material.IRON_HELMET), p1)
        env.fire(dispense)
        assertTrue(dispense.isCancelled)
    }

    @Test
    fun `pickup allowed while onemore`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        env.join(p1, arena)
        val pickup = EntityPickupItemEvent(p1, env.itemEntity(), 0)
        env.fire(pickup)
        assertFalse(pickup.isCancelled)
    }

    @Test
    fun `fertilize and sign change cancelled while restricted`() {
        val (p1, _) = env.twoPlayerIngame()

        val fertilize = BlockFertilizeEvent(env.plainBlock(), p1, listOf(env.plainBlock().state))
        env.fire(fertilize)
        assertTrue(fertilize.isCancelled)

        val sign = SignChangeEvent(env.blockOf(Material.OAK_SIGN), p1, listOf(Component.text("x")), Side.FRONT)
        env.fire(sign)
        assertTrue(sign.isCancelled)
    }

    private fun outsider() = env.player("Outsider")

    private fun entityPlace(player: Player?) = EntityPlaceEvent(
        env.spawn(EntityType.OAK_BOAT),
        player,
        env.plainBlock(),
        BlockFace.UP,
        EquipmentSlot.HAND,
    )

    private fun hangingPlace(player: Player?) = HangingPlaceEvent(
        env.spawn(EntityType.ITEM_FRAME) as ItemFrame,
        player,
        env.plainBlock(),
        BlockFace.EAST,
        EquipmentSlot.HAND,
        env.item(Material.ITEM_FRAME),
    )

    private fun bucketEmpty(player: Player) = PlayerBucketEmptyEvent(
        player,
        env.plainBlock(),
        env.plainBlock(),
        BlockFace.UP,
        Material.WATER_BUCKET,
        env.item(Material.WATER_BUCKET),
        EquipmentSlot.HAND,
    )

    private fun bucketFill(player: Player) = PlayerBucketFillEvent(
        player,
        env.plainBlock(),
        env.plainBlock(),
        BlockFace.UP,
        Material.BUCKET,
        env.item(Material.BUCKET),
        EquipmentSlot.HAND,
    )

    private fun bucketEntity(player: Player) = PlayerBucketEntityEvent(
        player,
        env.spawn(EntityType.COD),
        env.item(Material.WATER_BUCKET),
        env.item(Material.COD_BUCKET),
        EquipmentSlot.HAND,
    )

    @Suppress("DEPRECATION")
    private fun bucketFish(player: Player) = PlayerBucketFishEvent(
        player,
        env.spawn(EntityType.COD) as Fish,
        env.item(Material.WATER_BUCKET),
        env.item(Material.COD_BUCKET),
        EquipmentSlot.HAND,
    )

    private fun entityPickup(entity: LivingEntity) = EntityPickupItemEvent(entity, env.itemEntity(), 0)

    private fun attemptPickup(player: Player) = PlayerAttemptPickupItemEvent(player, env.itemEntity(), 0)

    private fun arrowPickup(player: Player) = PlayerPickupArrowEvent(
        player,
        env.itemEntity(),
        env.spawn(EntityType.ARROW) as AbstractArrow,
    )

    private fun harvest(player: Player) = PlayerHarvestBlockEvent(
        player,
        env.plainBlock(),
        EquipmentSlot.HAND,
        mutableListOf(env.item(Material.SWEET_BERRIES)),
    )

    private fun dispenseArmor(target: LivingEntity) = BlockDispenseArmorEvent(env.plainBlock(), env.item(Material.IRON_HELMET), target)

    private fun fertilize(player: Player?) = BlockFertilizeEvent(env.plainBlock(), player, listOf(env.plainBlock().state))

    private fun signChange(player: Player) = SignChangeEvent(
        env.blockOf(Material.OAK_SIGN),
        player,
        listOf(Component.text("x")),
        Side.FRONT,
    )

    private fun entityInteract(player: Player) = PlayerInteractEntityEvent(player, env.spawn(EntityType.ITEM_FRAME))

    private fun armorStandManipulate(player: Player) = PlayerArmorStandManipulateEvent(
        player,
        env.spawn(EntityType.ARMOR_STAND) as ArmorStand,
        env.item(Material.IRON_CHESTPLATE),
        ItemStack.empty(),
        EquipmentSlot.HAND,
        EquipmentSlot.HAND,
    )

    @Test
    fun `block place by a non participant is not cancelled`() {
        env.twoPlayerIngame()
        val place = outsider().simulation().placeBlock(Material.STONE, env.plainBlock().location)
        assertFalse(place.isCancelled)
    }

    @Test
    fun `air interact is not denied while restricted`() {
        val (p1, _) = env.twoPlayerIngame()
        val event = PlayerInteractEvent(p1, Action.RIGHT_CLICK_AIR, env.item(Material.STONE), null, BlockFace.SELF, EquipmentSlot.HAND)
        env.fire(event)
        assertNotEquals(Event.Result.DENY, event.useItemInHand())
    }

    @Test
    fun `spawn egg interact is allowed while onemore`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        env.join(p1, arena)
        val event = interact(p1, env.plainBlock(), item = env.item(Material.ZOMBIE_SPAWN_EGG))
        env.fire(event)
        assertNotEquals(Event.Result.DENY, event.useItemInHand())
    }

    @Test
    fun `non egg item interact is not denied while restricted`() {
        val (p1, _) = env.twoPlayerIngame()
        val event = interact(p1, env.plainBlock(), item = env.item(Material.STONE))
        env.fire(event)
        assertNotEquals(Event.Result.DENY, event.useItemInHand())
    }

    @Test
    fun `entity interact passes for a non participant`() {
        env.twoPlayerIngame()
        val event = entityInteract(outsider())
        env.fire(event)
        assertFalse(event.isCancelled)
    }

    @Test
    fun `entity interact passes while onemore`() {
        val arena = env.newArena()
        val p1 = env.player("Alice")
        env.join(p1, arena)
        val event = entityInteract(p1)
        env.fire(event)
        assertFalse(event.isCancelled)
    }

    @Test
    fun `armor stand manipulate passes for a non participant`() {
        env.twoPlayerIngame()
        val event = armorStandManipulate(outsider())
        env.fire(event)
        assertFalse(event.isCancelled)
    }

    @Test
    fun `entity place passes for a non participant`() {
        env.twoPlayerIngame()
        val event = entityPlace(outsider())
        env.fire(event)
        assertFalse(event.isCancelled)
    }

    @Test
    fun `entity place with no player passes`() {
        env.twoPlayerIngame()
        val event = entityPlace(null)
        env.fire(event)
        assertFalse(event.isCancelled)
    }

    @Test
    fun `hanging place passes for a non participant`() {
        env.twoPlayerIngame()
        val event = hangingPlace(outsider())
        env.fire(event)
        assertFalse(event.isCancelled)
    }

    @Test
    fun `hanging place with no player passes`() {
        env.twoPlayerIngame()
        val event = hangingPlace(null)
        env.fire(event)
        assertFalse(event.isCancelled)
    }

    @Test
    fun `bucket empty passes for a non participant`() {
        env.twoPlayerIngame()
        val event = bucketEmpty(outsider())
        env.fire(event)
        assertFalse(event.isCancelled)
    }

    @Test
    fun `bucket fill passes for a non participant`() {
        env.twoPlayerIngame()
        val event = bucketFill(outsider())
        env.fire(event)
        assertFalse(event.isCancelled)
    }

    @Test
    fun `bucket entity passes for a non participant`() {
        env.twoPlayerIngame()
        val event = bucketEntity(outsider())
        env.fire(event)
        assertFalse(event.isCancelled)
    }

    @Test
    fun `bucket fish passes for a non participant`() {
        env.twoPlayerIngame()
        val event = bucketFish(outsider())
        env.fire(event)
        assertFalse(event.isCancelled)
    }

    @Test
    fun `entity item pickup passes when the entity is not a player`() {
        env.twoPlayerIngame()
        val event = entityPickup(env.nonPlayer() as LivingEntity)
        env.fire(event)
        assertFalse(event.isCancelled)
    }

    @Test
    fun `entity item pickup passes for a non participant`() {
        env.twoPlayerIngame()
        val event = entityPickup(outsider())
        env.fire(event)
        assertFalse(event.isCancelled)
    }

    @Test
    fun `attempt pickup passes for a non participant`() {
        env.twoPlayerIngame()
        val event = attemptPickup(outsider())
        env.fire(event)
        assertFalse(event.isCancelled)
    }

    @Test
    fun `arrow pickup passes for a non participant`() {
        env.twoPlayerIngame()
        val event = arrowPickup(outsider())
        env.fire(event)
        assertFalse(event.isCancelled)
    }

    @Test
    fun `harvest passes for a non participant`() {
        env.twoPlayerIngame()
        val event = harvest(outsider())
        env.fire(event)
        assertFalse(event.isCancelled)
    }

    @Test
    fun `dispensed armor passes when the target is not a player`() {
        env.twoPlayerIngame()
        val event = dispenseArmor(env.nonPlayer() as LivingEntity)
        env.fire(event)
        assertFalse(event.isCancelled)
    }

    @Test
    fun `dispensed armor passes for a non participant`() {
        env.twoPlayerIngame()
        val event = dispenseArmor(outsider())
        env.fire(event)
        assertFalse(event.isCancelled)
    }

    @Test
    fun `fertilize passes for a non participant`() {
        env.twoPlayerIngame()
        val event = fertilize(outsider())
        env.fire(event)
        assertFalse(event.isCancelled)
    }

    @Test
    fun `fertilize with no player passes`() {
        env.twoPlayerIngame()
        val event = fertilize(null)
        env.fire(event)
        assertFalse(event.isCancelled)
    }

    @Test
    fun `sign change passes for a non participant`() {
        env.twoPlayerIngame()
        val event = signChange(outsider())
        env.fire(event)
        assertFalse(event.isCancelled)
    }

    @Test
    fun `inventory click with a non player clicker is not cancelled`() {
        val (p1, _) = env.twoPlayerIngame()
        val view = inventoryView(p1, env.server.createInventory(null, InventoryType.CHEST))
        view.player = mockk<HumanEntity>()
        val click = click(view)
        env.fire(click)
        assertFalse(click.isCancelled)
    }

    @Test
    fun `inventory click on the crafting grid is not cancelled`() {
        val (p1, _) = env.twoPlayerIngame()
        val crafting = mockk<Inventory>()
        every { crafting.type } returns InventoryType.CRAFTING
        val view = inventoryView(p1, crafting)
        val click = click(view)
        env.fire(click)
        assertFalse(click.isCancelled)
    }

    private fun onemorePlayer() = env.player("Alice").also { env.join(it, env.newArena()) }

    @Test
    fun `bucket fish cancelled while restricted`() {
        val (p1, _) = env.twoPlayerIngame()
        val event = bucketFish(p1)
        env.fire(event)
        assertTrue(event.isCancelled)
    }

    @Test
    fun `entity place passes while onemore`() {
        val event = entityPlace(onemorePlayer())
        env.fire(event)
        assertFalse(event.isCancelled)
    }

    @Test
    fun `hanging place passes while onemore`() {
        val event = hangingPlace(onemorePlayer())
        env.fire(event)
        assertFalse(event.isCancelled)
    }

    @Test
    fun `bucket empty passes while onemore`() {
        val event = bucketEmpty(onemorePlayer())
        env.fire(event)
        assertFalse(event.isCancelled)
    }

    @Test
    fun `bucket fill passes while onemore`() {
        val event = bucketFill(onemorePlayer())
        env.fire(event)
        assertFalse(event.isCancelled)
    }

    @Test
    fun `bucket entity passes while onemore`() {
        val event = bucketEntity(onemorePlayer())
        env.fire(event)
        assertFalse(event.isCancelled)
    }

    @Test
    fun `bucket fish passes while onemore`() {
        val event = bucketFish(onemorePlayer())
        env.fire(event)
        assertFalse(event.isCancelled)
    }

    @Test
    fun `entity item pickup passes while onemore`() {
        val event = entityPickup(onemorePlayer())
        env.fire(event)
        assertFalse(event.isCancelled)
    }

    @Test
    fun `attempt pickup passes while onemore`() {
        val event = attemptPickup(onemorePlayer())
        env.fire(event)
        assertFalse(event.isCancelled)
    }

    @Test
    fun `arrow pickup passes while onemore`() {
        val event = arrowPickup(onemorePlayer())
        env.fire(event)
        assertFalse(event.isCancelled)
    }

    @Test
    fun `harvest passes while onemore`() {
        val event = harvest(onemorePlayer())
        env.fire(event)
        assertFalse(event.isCancelled)
    }

    @Test
    fun `dispensed armor passes while onemore`() {
        val event = dispenseArmor(onemorePlayer())
        env.fire(event)
        assertFalse(event.isCancelled)
    }

    @Test
    fun `armor stand manipulate passes while onemore`() {
        val event = armorStandManipulate(onemorePlayer())
        env.fire(event)
        assertFalse(event.isCancelled)
    }

    @Test
    fun `fertilize passes while onemore`() {
        val event = fertilize(onemorePlayer())
        env.fire(event)
        assertFalse(event.isCancelled)
    }

    @Test
    fun `sign change passes while onemore`() {
        val event = signChange(onemorePlayer())
        env.fire(event)
        assertFalse(event.isCancelled)
    }
}
