# Game Flow Specification

## State Transitions

Each arena owns exactly one `ArenaMatch` holding its state.

```
WAITING ──join(1st)──▶ ONEMORE ──join(2nd)──▶ COUNTDOWN ──▶ INGAME
   ▲                      │                                     ▲  │
   │                      │ leaveWaiting(voluntary leave)       │  │ recordDefeat
   │                      ▼                                     │  ▼
   │                    WAITING                            ROUNDCOUNTDOWN
   │                                                        (round decided)
   └────────── abort / finishMatch / forfeit ◀───────────────────┘
```

| State            | Meaning                                              |
| ---------------- | ---------------------------------------------------- |
| `WAITING`        | 0 participants. Accepting joins                      |
| `ONEMORE`        | 1 participant. Waiting for one more. Accepting joins |
| `COUNTDOWN`      | Both players present. Initial countdown running      |
| `INGAME`         | Match in progress                                    |
| `ROUNDCOUNTDOWN` | Preparing next round after a round was decided       |

Joins are accepted only in `WAITING` / `ONEMORE`.

## Joining

- Players join by right-clicking a Join sign (see signs.md).
- 1st player: transitions to `ONEMORE` and shows "Waiting for one more player."
- 2nd player: transitions to `COUNTDOWN` and starts the initial countdown.
- Join conditions: arena is `enabled`, state accepts joins, not in another arena, not full, not a duplicate join by the same player.
- If the player has an unrestored inventory backup, restore completes before registration (join is refused while the restore target is dead).

## Initial Countdown (COUNTDOWN)

- Starts after 0.5s, then counts `remaining = 5 → 0` at 1s intervals.
- `remaining > 0`: notify both with "Starting in: Ns".
- `remaining == 0`: run start processing.
  - If either player is dead, postpone the start (re-evaluated next tick).
  - Bulk-backup both players' inventories (`inv.<name>` in `players.yml`).
  - Apply the arena kit, teleport to spawn 1 / 2, run `prepareForMatch` (health, hunger, effect reset).
  - Notify "Game Start!", transition to `INGAME`, show the scoreboard.
- If a player handle becomes unavailable during the countdown, the match aborts.

## Round Resolution (recordDefeat)

- Defeat causes: `DEATH` (death) and `FALL` (falling at or below the destination world's minimum height).
- Acceptance: death/fall during `INGAME`, or a fall during `ROUNDCOUNTDOWN`. Death is accepted only in `INGAME`.
- Both players must be present and no defeat resolution may be in progress (duplicate-resolution guard `resolving`).
- If the winner's total wins reach `required-wins - 1`, treat it as the final kill and end the match (the final kill is not added to the win count). In other words, first to `required-wins` kills.
- Otherwise: winner gets +1 win, transition to `ROUNDCOUNTDOWN`, notify the round count (total wins) and winner name, play the round-end sound at the loser's position.

### Inter-Round Processing (ROUNDCOUNTDOWN)

- Winner: immediately `prepareForMatch` + re-apply kit + teleport to spawn.
- Loser (dead): respawn next tick → `prepareForMatch` + apply kit + teleport to spawn → release the resolution guard.
- Loser (non-death e.g. fall): same processing immediately, release the resolution guard next tick.
- Round countdown: `remaining = 7` at 1s intervals.
  - `7`: re-apply kit + `prepareForMatch` for both.
  - `1..5`: notify "Starting in: Ns".
  - `0`: notify "Start!" and return to `INGAME`.

## Match End (finishMatch)

- Broadcast the victory notice "Player Y won in arena X!".
- Winner: if alive, immediately reset health + restore inventory + send to lobby + victory fireworks. If dead, respawn next tick then same processing. No fireworks on a forfeit.
- Loser (dead): respawn next tick → reset health → restore inventory → send to lobby.
- Loser (alive): reset health (except on forfeit) + restore inventory + send to lobby (no teleport on forfeit).
- Stats: record winner `win + 1`, loser `lose + 1` to `stats/<uuid>.yml`. A recording failure only logs a warning; teardown continues.
- State returns to `WAITING`; participants and win counts are cleared. The sign is updated to `Waiting`.

## Leaving, Disconnecting, Aborting

- `/1vs1 leave`: voluntary leave is allowed only during `ONEMORE` (inventory untouched). Otherwise "You cannot leave the arena during the countdown!".
- Logout: before the match starts (`ONEMORE`, initial `COUNTDOWN`), unregister only. On disconnect during `COUNTDOWN`, the remaining participant keeps waiting in `ONEMORE` and the running countdown is invalidated by generation-token mismatch. If the match is in progress (`ROUNDCOUNTDOWN`/`INGAME`), the match ends as a forfeit with the opponent as winner.
- Abort: on arena disable/remove, shutdown, or start failure. Deferred tasks such as countdowns are invalidated by generation-token mismatch. Every participant's inventory is restored (dead players respawn next tick, then restore).
- Shutdown: abort all matches and synchronously restore unrestored backups of online participants. Dead players only get inventory restore + scoreboard clear; their records remain (restored again on next login).

## Inventory Backup/Restore

- Just before the match starts, both players' inventories are bulk-saved to `players.yml` (if any save fails, no record is changed and the match aborts).
- Restore is managed via `RestoreTicket`. UUID takes precedence; a name is only used when the backup's uuid matches (or is unrecorded), preventing mis-restoration through name reuse.
- After restore completes, the backup is deleted. If deletion fails the record remains and is restored again on next startup (fail-safe).
- Unrestored backups are restored on relogin and on rejoin.

## Scoreboard

- Title: arena name (green, bold). Entries: participant names (gold) + win counts.
- Updated at match start and on each round resolution. Cleared when restore completes.

## Configuration

| config.yml key  | Default         | Meaning                            |
| --------------- | --------------- | ---------------------------------- |
| `prefix`        | `&8[&61vs1&8] ` | Prefix for all messages (& format) |
| `required-wins` | `3`             | Kills required to win a match      |
