# Command Specification

The main command is `/1vs1`. With no arguments or an unknown subcommand it
returns usage. Arena names resolve case-insensitively (matching the
case-insensitive duplicate rejection at creation).

## General Commands

### `/1vs1 stats [player]`

- Player only (not console).
- No argument: shows the executor's own stats.
- With argument: exact-match online lookup (equivalent to `getPlayerExact`) → cached offline players → otherwise resolve the UUID asynchronously and read `stats/<uuid>.yml`.
- Display: `Win: N`, `Lose: N`, `W/L (win rate): N.NN` (see stats.md).
- If no stats file exists: "No stats found".

### `/1vs1 leave`

- Player only.
- Leaving succeeds only while waiting in `ONEMORE` ("You left the arena").
- In any other state: "You cannot leave the arena during the countdown!".
- If not participating: "You are not in an arena!".

## Admin Commands (OP only)

Returns "You don't have permission!" when the sender lacks permission.

### `/1vs1 setlobby`

- Player only. Saves the executor's position as the lobby (post-match return point) in `lobby.yml`.

### `/1vs1 arena info [arena]`

- No OP required. Shows the arena's state.
- Display: `=== Arena[name] ===`, state (`Waiting` / `1 More` / `Countdown` / `Ingame`).
- During `INGAME` / `ROUNDCOUNTDOWN` with 2 participants, also shows the matchup (`[A] vs [B]`) and wins (`a-b`).
- Unknown arena: "That arena does not exist".

### `/1vs1 arena create [arena]`

- Creates an arena. Name must be ≤64 chars, no leading/trailing whitespace, and must not contain `/` `\` `.` `:` control characters, or the reserved name `players` (case-insensitive).
- If the name already exists (case-insensitive): "That arena already exists".

### `/1vs1 arena remove [arena]`

- Removes an arena. If a match is in progress it is aborted and participants are restored.
- Also deletes `arena/<name>.yml` (including sign registration) and `status/<name>.yml`.
- If it does not exist: "That arena does not exist".

### `/1vs1 arena setspawn1|setspawn2 [arena]`

- Player only. Saves the executor's position as spawn 1 / 2.

### `/1vs1 arena enable|disable [arena]`

- Enables/disables. If already in that state: an "already enabled/disabled" message.
- `disable` aborts a match in progress.

### `/1vs1 arena setInv [arena]`

- Player only. Saves the executor's current equipment/inventory as the arena kit in the `inventory` section of `arena/<name>.yml`.

### `/1vs1 arena setsign [arena]`

- Player only. Registers the sign being looked at (within 10 blocks) as the join sign.
- If not looking at a sign: "Look at a sign and run the command".
- If that sign is already registered to another arena: "That sign is already registered".
- A registered sign cannot be destroyed.

### `/1vs1 arena removesign [arena]`

- Unregisters the arena's sign. The sign block itself remains and becomes breakable.
- If no sign is registered: "No sign is registered for that arena".

## Tab Completion

- 1st argument: `stats` `leave` `arena` (plus `setlobby` for OP).
- `arena` 2nd argument: all subcommands for OP, only `info` for non-OP.
- `arena` 3rd argument: registered arena names.
