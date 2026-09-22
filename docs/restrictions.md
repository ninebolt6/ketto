# Restrictions on Participants (Event Handling)

Restrictions applied to players participating in an arena. Per-state decisions
are centralized in `domain/ParticipantRestrictions`; listeners only convert
events. Listeners are split into three by responsibility: `ArenaMatchListener`
(match-progression events: death, damage, quit, join, move, commands),
`ArenaGuardListener` (protection of blocks, inventories, entity interactions),
and `ArenaTeleportListener` (movement restriction for teleport/vehicle).
`ArenaSignListener` handles join signs.

## Restrictions by State

| State            | X/Z move freeze | Damage           | Block break | Block place | Item removal        | Pickup/acquire | Teleport            | Commands |
| ---------------- | --------------- | ---------------- | ----------- | ----------- | ------------------- | -------------- | ------------------- | -------- |
| `WAITING`        | -               | -                | -           | -           | -                   | -              | -                   | denied   |
| `ONEMORE`        | -               | -                | -           | -           | -                   | -              | -                   | allowed  |
| `COUNTDOWN`      | -               | -                | -           | -           | -                   | -              | pearl+internal only | denied   |
| `ROUNDCOUNTDOWN` | frozen          | all nullified    | denied      | denied      | denied (drop/store) | denied         | internal only       | denied   |
| `INGAME`         | -               | only vs opponent | denied      | denied      | denied (drop/store) | denied         | pearl+internal only | denied   |

## Behavior by Event

### PlayerDeathEvent (HIGH)

- On participant death: `keepInventory = true` empties drops.
- Experience is also preserved: `droppedExp = 0` prevents the drop and `keepLevel = true` keeps level/xp after respawn.
- If accepted as a defeat, round/match resolution runs. If not accepted (wrong state, resolution in progress), only a respawn is scheduled for the next tick.

### EntityDamageEvent (HIGH)

- For `EntityDamageByEntityEvent` (entity-caused): resolve the responsible party via `damageSource.causingEntity` (traces projectile shooters, placers, etc.). If victim or attacker is a restricted participant, allow only "INGAME, same-match opponent or self" damage and cancel everything else. Blocks interference from mobs, third parties, and other matches, plus participant → outsider/mob attacks.
- Non-entity damage (fall, fire, lava, suffocation, etc.) cannot be attributed, so it is cancelled only in damage-immune states; in INGAME it is accepted as a defeat as before.

### PlayerMoveEvent

- `PlayerTeleportEvent` has its own HandlerList and never reaches this handler (teleports are restricted by ArenaTeleportListener).
- In an X/Z-frozen state, a move that changes the block position is reverted with `setTo(from)`.
- During `INGAME` / `ROUNDCOUNTDOWN` with 2 participants, falling at or below the destination world's minimum height resolves as a fall defeat.

### PlayerTeleportEvent / PlayerPortalEvent

- Participant teleports are judged by per-state cause restriction (pearl+internal only / internal only / unrestricted).
- The plugin's own teleports are distinguished by a marker that identifies the synchronously fired event.
- `PlayerPortalEvent` has its own HandlerList and is handled separately.
- While movement is frozen, `VehicleEnterEvent` also blocks mounting.

### BlockBreakEvent

- Cancelled while block breaking is denied.

### BlockPlaceEvent

- Cancelled while block placing is denied. Since placed blocks cannot be removed while breaking is denied, this prevents arena pollution and camping.
- Flint and steel is allowed as ignition rather than placement.
- `EntityPlaceEvent` / `HangingPlaceEvent` (placing boats, minecarts, armor stands, item frames, paintings), `PlayerBucketEmptyEvent` (placing liquids), `BlockFertilizeEvent` (bone-meal growth), and `SignChangeEvent` (rewriting unwaxed signs) are cancelled under the same restriction.
- `PlayerBucketFillEvent` / `PlayerBucketEntityEvent` (bucket pickup, mob capture) are cancelled under the same restriction as block breaking.

### PlayerDropItemEvent

- Cancelled while dropping is denied. After the kit swap the inventory is overwritten by the start-of-match backup, so this prevents kit items from being carried out.

### PlayerInteractEvent

- Right-clicks on join signs are handled by ArenaSignListener (join behavior in signs.md).
- On the ArenaGuardListener side, interacting with storable blocks (containers, ender chests, beds, respawn anchors, flower pots) is denied while inventory transfer is denied, and spawn-egg use is denied while block placing is denied.

### PlayerInteractEntityEvent / PlayerInteractAtEntityEvent / PlayerArmorStandManipulateEvent

- While inventory transfer is denied, interaction with inventory-holding entities (villagers, chest minecarts, etc.), item frames, and armor stands is cancelled.
- Bukkit gives each event class its own HandlerList, so subclasses are handled separately.

### InventoryClickEvent / InventoryDragEvent

- While inventory transfer is denied, all operations are cancelled whenever a foreign inventory (anything other than `CRAFTING`/`PLAYER`) is open (second-line defense in case the interact side is bypassed).

### EntityPickupItemEvent / PlayerAttemptPickupItemEvent / PlayerPickupArrowEvent

- Cancelled while pickup is denied. Includes arrow/trident retrieval (blocks supplies shot in by spectators from being collected).

### PlayerHarvestBlockEvent

- Cancelled while pickup is denied (berry-type harvests go straight to the inventory without a pickup event).

### BlockDispenseArmorEvent

- Cancels a dispenser equipping armor onto a restricted participant.

### PlayerCommandPreprocessEvent

- Cancelled while commands are denied; returns "Commands cannot be used!".
- Commands are allowed only while waiting in `ONEMORE`.

### PlayerQuitEvent

- A disconnecting player can no longer be fetched from `Server`, so join/restore processing runs in a scope that can resolve the event's `Player` only during synchronous handling.
- Even without participating, an unrestored backup is restored before the disconnect completes.

### PlayerJoinEvent

- If the player is not participating and has an unrestored backup, restore it.

## Known Residual Risks

- Buffs/debuffs from third-party splash/lingering potions (out of scope: not a damage event and attribution is complex).
- Knockback from wind charges etc. (damage is blocked but knockback may remain).
- Environmental interference that cannot be attributed, e.g. third-party lava flowing into an open arena, is left to physical arena enclosure or region protection.
- Explosive destruction of arena blocks via igniting pre-existing TNT (flint and steel is allowed) depends on arena design.
