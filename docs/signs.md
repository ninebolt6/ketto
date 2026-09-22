# Sign Specification

## Registration

- `/1vs1 arena setsign [arena]` registers the sign being looked at. Coordinates are saved to the `sign` section of `arena/<name>.yml`.
- One sign belongs to one arena. A sign already registered to another arena is refused.
- A registered sign cannot be destroyed by anyone (including OPs). It is unregistered via `/1vs1 arena removesign` or by removing the arena.
- Explosions exclude it from the block list to prevent its loss. Destruction of the supporting block under the sign, and changes by server commands (`/setblock` etc.) or other plugins, are out of scope.
- When an arena is removed, `arena/<name>.yml` is deleted with it and the sign registration disappears.

## Display

Sign lines:

| Line | Contents          |
| ---- | ----------------- |
| 1    | `§4[§6§l1vs1§4]`  |
| 2    | Arena name (aqua) |
| 3    | State (see below) |
| 4    | (empty)           |

| State                        | Line 3          |
| ---------------------------- | --------------- |
| Joinable (WAITING/ONEMORE)   | `§9Join`        |
| Not joinable (anything else) | `§4Cannot join` |

The sign display is updated on every state transition (on startup all arenas initialize to `Waiting`).

## Join Behavior

- Right-clicking a sign (main hand) runs join processing if the registered arena is accepting joins.
- Handled clicks are cancelled. An unwaxed sign opens the edit screen on right-click, so vanilla sign editing and item use are both denied.
- Right-clicking a sign whose arena is not accepting joins shows "This arena is currently in a game".
- Join result messages:
  - 1st player: "Joined arena: X" + "Waiting for one more player."
  - 2nd player: "Joined arena: X" (countdown starts)
  - Already in another arena: "You are already in another arena"
  - Not enabled: "The arena is not enabled!"
  - Full/in progress: "This arena is currently in a game"
