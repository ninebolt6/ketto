# 1vs1

A 1-on-1 arena PvP plugin for Paper servers.

Two players join a match by clicking a join sign. The plugin backs up their
inventories, applies the arena kit, teleports them to the arena spawns, and
runs rounds until one player reaches `required-wins` wins. When the match ends, inventories are
restored and players return to the lobby.

## Features

- Multiple arenas, each with two spawn points and a custom kit
- Join signs protected from destruction while registered
- First-to-`required-wins` rounds with round countdowns and a scoreboard
- Inventory backup/restore with recovery for interrupted matches
- Per-player win/lose stats (`/1vs1 stats`)
- Messages localized to each player's client locale

## Requirements

- Paper 1.21.3 or later
- Java 21

## Installation

Drop the jar into `plugins/` and restart the server.

## Quick setup

```
/1vs1 lobby set                      # where players return after a match
/1vs1 arena create <arena>
/1vs1 arena <arena> spawn set 1      # run while standing at spawn 1
/1vs1 arena <arena> spawn set 2      # run while standing at spawn 2
/1vs1 arena <arena> kit set          # copies your inventory as the arena kit
/1vs1 arena <arena> sign set         # while looking at a sign: makes it a join sign
/1vs1 arena <arena> enable
```

Players then right-click the sign to join. First click waits for an opponent;
a second player starts the countdown.

## Commands

| Command                                | Permission   | Description                                        |
| -------------------------------------- | ------------ | -------------------------------------------------- |
| `/1vs1 stats [player]`                 | everyone     | Show your own or another player's stats            |
| `/1vs1 leave`                          | everyone     | Leave while waiting for an opponent                |
| `/1vs1 arena <arena>`                  | everyone     | Show arena state and current matchup               |
| `/1vs1 lobby set`                      | `1vs1.admin` | Save your position as the lobby                    |
| `/1vs1 arena create <arena>`           | `1vs1.admin` | Create an arena                                    |
| `/1vs1 arena <arena> remove`           | `1vs1.admin` | Remove an arena (aborts any match)                 |
| `/1vs1 arena <arena> spawn set <1\|2>` | `1vs1.admin` | Save your position as a spawn                      |
| `/1vs1 arena <arena> kit set`          | `1vs1.admin` | Copy your inventory as the arena kit               |
| `/1vs1 arena <arena> sign set`         | `1vs1.admin` | Register the sign you're looking at as a join sign |
| `/1vs1 arena <arena> sign remove`      | `1vs1.admin` | Unregister the arena's join sign                   |
| `/1vs1 arena <arena> enable\|disable`  | `1vs1.admin` | Enable/disable joins (disable aborts a match)      |

Admin commands require the `1vs1.admin` permission (default: server operators).
`create` is a reserved word and cannot be used as an arena name.
Arena names containing spaces can be used by quoting them, e.g. `/1vs1 arena "my arena"`.

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
