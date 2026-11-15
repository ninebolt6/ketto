---
name: e2e-verify
description: Verify observable plugin behavior autonomously on real Paper servers with MCProtocolLib bots
argument-hint: "[behavior-or-scenario]"
allowed-tools:
  - read
  - grep
  - glob
  - exec
---

# Real-server verification

Use after MockBukkit tests pass for any change to observable in-game behavior,
commands, persistence/recovery, or plugin loading. No human Minecraft client is
needed: the suite downloads Paper, starts isolated offline servers, and connects
headless players. E2E runs separately from `check`/`build` and needs network access
on the first run. Use the project's Nix shell JDK.

## Run

```sh
./gradlew e2eTest
./gradlew -PpaperNext e2eTest
./gradlew e2eTest --tests 'net.ninebolt.ketto.e2e.MatchE2ETest.players complete*'
```

Run a targeted scenario while developing, then both Paper lines before reporting
compatibility. A successful startup alone does not verify the changed behavior.

## Write a scenario

- Read the changed production path, README, messages, and existing scenarios.
  Define assertions that would fail if the behavior were broken.
- Add a JUnit test under `src/e2eTest/kotlin/net/ninebolt/ketto/e2e`, using
  `@ExtendWith(PaperServerExtension::class)` and a `PaperServer` parameter.
  The extension owns teardown, including on failure. Use sentence-style backtick
  method names. Configure the plugin before starting Paper.
- Reuse `fixtures/DuelScenario` for command-driven arena setup and matches, or
  `PaperServer` and `server.bot(name)` for a smaller scenario. Fixtures belong in
  the `fixtures/` package. Use `required-wins: 1` unless testing multiple rounds.
- Every player action under test must go through `Bot`: commands, sign clicks
  (`useBlock`), attacks, disconnects, and reconnects. Never call plugin handlers
  or services. RCON is only for setup and authoritative assertions, not to
  produce the behavior being verified.
- Reuse `ServerActions.kt`: `standAt`, `give`, `placeSign`, `awaitItem`,
  `awaitNoItem`, `playerPosition`, `awaitPosition`, `awaitSign`, `waitTicks`, and
  `commandExpect`. Prefer vanilla `minecraft:` item predicates and server-side
  positions over interpreting protocol numeric item IDs.
- Mark messages **before** an action, then await its reply. `awaitMessage`
  consumes through its successful match; later waits cannot rewind, even with an
  older explicit mark. `commandExpect` already marks before sending the command.

```kotlin
val reply = player.markMessages()
player.command("ketto stats")
player.awaitMessage(after = reply) { it.contains("Win: 1") }
player.awaitMessage { it.contains("Lose: 0") }
```

Keep a scenario when it protects a durable user-visible contract or reproduces
a regression requiring the real server. Delete a one-off diagnostic probe after
recording its evidence if existing permanent scenarios already cover the
contract; never delete or weaken a scenario just because it found a plugin bug.
Distinguish harness failures from plugin failures before changing either.

## Evidence and debugging

Each test writes `build/e2e/<Class>/<method>/`:

- `server.log`: startup, plugin lifecycle, commands, joins, and exceptions.
- `paper-build.txt`: resolved Paper version/build for reproducing this run.
- `rcon.log`: setup commands and authoritative assertion responses.
- `bots/<name>.log`: connection/configuration events, teleports, health, chat,
  actions, and message-wait boundaries.

Read the failing assertion and JUnit report in `build/reports/tests/e2eTest/`,
then correlate the three logs. Check that the bot joined and loaded, the action
was actually sent, and the server observed the expected state transition. Use
RCON evidence to distinguish stale client state from plugin behavior. Preserve
needed logs before rerunning: the same test directory is recreated. Report the
command, result, timing, and relevant log excerpts; do not report a pass from a
build or a page that merely loads. CI uploads these diagnostics on failure.

## Version bumps and gotchas

- Update `paper` and `next-paper` in `gradle/libs.versions.toml`: E2E derives
  server versions from both and pins the next build from `next-paper`. Stable
  resolves the latest build on every run; record `paper-build.txt` with results.
  Update MCProtocolLib pins (`mcprotocollib`, `next-mcprotocollib`) as appropriate.
  run-paper verifies SHA-256 and caches downloads in
  `~/.gradle/caches/run-task-jars/paper`. Preparation always runs and disables
  run-paper's update-check interval, so cached downloads do not freeze latest.
  Per-test servers share patched libraries from `build/paper-e2e/`.
- Keep protocol API differences in `src/e2eTest/kotlin-paper` and
  `src/e2eTest/kotlin-paperNext`, selected by `paperNext`; keep common behavior
  single-source. Attack packets and respawn enum names currently differ.
- Client information (`en_us`) must be sent during configuration, not immediately
  on login completion. Clients must confirm teleports and send player-loaded and
  chunk-batch acknowledgements; retain the fixture's handling.
- Vanilla hides unauthorized command branches; permission rejection can arrive
  as `command.unknown.argument`. Plain serialization preserves vanilla
  translation keys; Ketto messages arrive as localized text.
- Fresh joins have spawn invulnerability. Wait server ticks with `waitTicks`,
  not arbitrary wall-clock sleeps, before testing immediate combat.
- Use `execute anchored eyes ... facing ...` for sign targeting (`standAt`
  handles this). Read individual sign lines: aggregate NBT output is truncated.
- Before a crash-recovery kill, use `save-all flush` to persist in-match player
  data. Then `kill()`/`start()` the same server directory and reconnect through
  the bots; otherwise old player data can make inventory recovery appear to work.
- Bot auto-respawn defaults to enabled; disable it explicitly when testing death
  screens or deferred recovery. Use bounded waits, not indefinite loops.
