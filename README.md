# ketto

A 1-on-1 arena PvP plugin for Paper servers.

Two players join a match by clicking a join sign. The plugin backs up their
inventories, clears their potion effects, applies the arena kit, teleports them
to the arena spawns, and runs rounds until one player reaches `required-wins`
wins. When the match ends, inventories are restored, potion effects are cleared
again, and players return to the lobby. Potion effects are not backed up, so
effects active before the match are not restored.

## Features

- Multiple arenas, each with two spawn points and a custom kit
- Join signs protected from destruction while registered
- First-to-`required-wins` rounds with round countdowns and a scoreboard
- Inventory backup/restore with recovery for interrupted matches
- Per-player win/lose stats (`/ketto stats`)
- Messages localized to each player's client locale

## Requirements

- Paper 1.21.11 or later
- Java 21 or later. Paper 26.x is also tested and requires Java 25.

## Installation

Drop the jar into `plugins/` and restart the server.

## Quick setup

```
/ketto lobby set                      # where players return after a match
/ketto arena create <arena>
/ketto arena <arena> spawn set 1      # run while standing at spawn 1
/ketto arena <arena> spawn set 2      # run while standing at spawn 2
/ketto arena <arena> kit set          # copies your inventory as the arena kit
/ketto arena <arena> sign set         # while looking at a sign: makes it a join sign
/ketto arena <arena> enable
```

Players then right-click the sign to join. First click waits for an opponent;
a second player starts the countdown.

## Commands

| Command                                 | Permission    | Description                                        |
| --------------------------------------- | ------------- | -------------------------------------------------- |
| `/ketto stats [player]`                 | everyone      | Show your own or another player's stats            |
| `/ketto leave`                          | everyone      | Leave while waiting for an opponent                |
| `/ketto arena <arena>`                  | everyone      | Show arena state and current matchup               |
| `/ketto lobby set`                      | `ketto.admin` | Save your position as the lobby                    |
| `/ketto arena create <arena>`           | `ketto.admin` | Create an arena                                    |
| `/ketto arena <arena> remove`           | `ketto.admin` | Remove an arena (aborts any match)                 |
| `/ketto arena <arena> spawn set <1\|2>` | `ketto.admin` | Save your position as a spawn                      |
| `/ketto arena <arena> kit set`          | `ketto.admin` | Copy your inventory as the arena kit               |
| `/ketto arena <arena> sign set`         | `ketto.admin` | Register the sign you're looking at as a join sign |
| `/ketto arena <arena> sign remove`      | `ketto.admin` | Unregister the arena's join sign                   |
| `/ketto arena <arena> enable\|disable`  | `ketto.admin` | Enable/disable joins (disable aborts a match)      |

Admin commands require the `ketto.admin` permission (default: server operators).
`create` is a reserved word and cannot be used as an arena name.
Arena names containing spaces can be used by quoting them, e.g. `/ketto arena "my arena"`.

## Configuration (`config.yml`)

```yaml
# Chat language. auto = each player's client locale; a code such as ja/en fixes it server-wide
language: auto
# Fallback + language for shared surfaces (signs, scoreboard, etc.)
default-language: en
required-wins: 3
```

Additional languages can be added as `messages/<lang>.yaml`.

Build instructions and contribution flow live in [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[MIT](LICENSE)
