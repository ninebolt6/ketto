# Data Format Specification

Everything is YAML under the plugin data folder (`plugins/1vs1/`). Arena state
is managed in memory; YAML is only for persistence.

```
plugins/1vs1/
├── config.yml            # global config (admin-edited only; the plugin never writes it)
├── lobby.yml             # lobby coordinates
├── arenalist.yml         # list of arena names
├── arena/<name>.yml      # arena definition (enabled, spawns, kit, sign)
├── status/<name>.yml     # arena state snapshot
├── status/players.yml    # join registrations + unrestored backups
└── stats/<uuid>.yml      # stats
```

Saves always write to `*.tmp` first, then atomically replace (plain move on
filesystems without ATOMIC_MOVE). YAML parse failures and I/O failures are
converted to `PersistenceFailure`.

## config.yml

Only settings edited by an admin. The plugin never writes this file.

```yaml
prefix: "&8[&61vs1&8] " # message prefix (& format)
required-wins: 3 # kills required to win a match
```

## lobby.yml

```yaml
# Lobby (set via /1vs1 setlobby)
lobby:
  world: world
  x: 0.0
  y: 0.0
  z: 0.0
  yaw: 0.0
  pitch: 0.0
```

## arenalist.yml

```yaml
arenas:
  - <name>
```

- On startup, arenas load in this order. Invalid or duplicate names (case-insensitive) are warned and skipped.

## arena/<name>.yml

```yaml
enabled: true
spawn1: { world: world, x: 0.0, y: 0.0, z: 0.0, yaw: 0.0, pitch: 0.0 }
inventory:
  armor: [ItemStack, ...] # Bukkit YAML serialization format
  item: [ItemStack, ...]
sign: # join sign (set via /1vs1 arena setsign)
  world: world
  x: 0.0
  y: 0.0
  z: 0.0
```

- Saving `enabled` / `spawnN` preserves the `inventory` and `sign` sections (owned by `setInv` / `setsign` respectively).
- yaw/pitch are stored and read at float precision.

## status/<name>.yml

```yaml
status: WAITING # ArenaState name
players: [<name>, ...] # participant names
win: { <name>: <wins> } # win counts (name-keyed)
```

- A reference snapshot overwritten on every state transition. Never used for restore.

## status/players.yml

```yaml
players: [<name>, ...] # names of registered participants
arena:
  <name>: <arena> # player name -> arena name
inv:
  <name>: # unrestored backup (player-name key)
    armor: [ItemStack, ...]
    item: [ItemStack, ...]
    uuid: <uuid> # owner UUID
    id: <uuid> # backup identifier
    match: <uuid> # match identifier
  <name>__<id>: # evacuation target for a same-named record owned by someone else
    name: <name> # original player name
    # other fields same as <name>
```

- `players` / `arena.*`: join registration. Cleared on leave, match end, or abort (backup `inv.*` is untouched). Registrations are cleared on startup.
- `inv.*`: bulk-saved at match start; deleted once restore completes. Deletion only removes records whose `id` (or `uuid` when absent) matches, so name reuse never deletes another player's data.
- If a record owned by someone else (uuid mismatch) remains under the same name key, the save side first evacuates the old record to `inv.<name>__<id>` before writing. The two are distinguished by `id` and restored/deleted independently.
- For backups without `uuid`, `playerId` is treated as null. Legacy records without `id` are assigned one and written back on load.
- Records without `uuid` can only be matched to an owner by name, so in offline mode (`online-mode=false`) they are not restored and a warning is logged on startup. They are kept until an admin fills in `uuid` or deletes the record.

## stats/<uuid>.yml

```yaml
win: 0
lose: 0
```

- Missing file means "no stats" (null). Corruption is `PersistenceFailure`.
