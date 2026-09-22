# Stats Specification

## Recording

- On match end, record winner `win + 1` and loser `lose + 1` to `stats/<uuid>.yml`.
- The record key is the player UUID. File format is `win` / `lose` integers (see data-format.md).
- A recording failure is only reported as a warning log; teardown (restore, lobby transfer) continues.

## Display (`/1vs1 stats`)

```
Win: <wins>
Lose: <losses>
W/L (win rate): <ratio>
```

- `ratio` is `win / lose` rounded to 2 decimals with `HALF_UP`.
- When `lose == 0`, it is computed as `win / 1`.
- A player without a stats file gets "No stats found".
- Lookups with an argument are rate-limited to one per 3 seconds per executor (UUID resolution of uncached names involves an external lookup). Viewing your own stats without an argument is exempt.
