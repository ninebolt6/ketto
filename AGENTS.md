# 1vs1

A 1-on-1 arena PvP plugin for Minecraft.

## Commands

```sh
./gradlew clean build --warning-mode all        # compile + JUnit tests + jar
nix develop                                     # shell with JDK 21 + actrun
actrun workflow run .github/workflows/ci.yml    # run CI locally
actrun lint                                     # static check of workflows
```

## Architecture

- Dependency direction is `infrastructure → application → domain` (inward only,
  enforced by `ArchitectureTest`). `OneVsOnePlugin` is the composition root and
  wires all dependencies manually
- application connects to the outside through interfaces in `port/`. See docs/
  for detailed specs

## Domain

- Only immutable values
- Domain values with invariants keep their constructor private and are created
  only through companion factory functions (`of`/`new`/`restored`) — types
  without invariants, such as operation results, are exempt. Values the caller
  should not supply (e.g. new ids) are generated inside the factory; rebuilding
  an existing id goes through `restored` or a `new` overload that takes the id
- Stateless predicates purely derived from values (`TeleportRestriction.allows`,
  `DamageAdmission.allows`, etc.) may live in domain
- One concept per file

## Tests

- Use `kotlin.test` assertions (not `org.junit.jupiter.api.Assertions`)
- domain/application use pure tests + fakes (via `TestApp`).
  infrastructure uses MockBukkit (mock + manual wiring via `TestEnv`) and
  asserts on real state
- Listener tests register via `env.registerListeners()` and verify through the
  real dispatch of `env.fire(event)`. Never call handler methods directly
  (that would miss forgotten `@EventHandler` registration or events lost to
  custom HandlerLists)
- Prefer real actions and simulate for event creation: `PlayerSimulation`
  (`PlayerMock.simulate*` is a delegating shim and deprecated),
  `simulateDamage` + a real `DamageSource.builder`, `disconnect()`,
  `teleport()`, `reconnect()`. Build a fixture and `fire` it only for events
  that have no simulate
- Verify fired events with `env.assertFired<T> { }` (MockBukkit's
  assertEventFired family is deprecated)
- MockK only for limited use: fault injection, APIs MockBukkit does not
  implement, etc.
- Test helpers (fakes/fixtures/TestApp/TestEnv, etc.) go in the `fixtures/`
  subpackage of each layer

## Style

- Comments must not repeat what names and signatures already convey. Write only
  intent, constraints, and non-obvious rationale
- Do not write fully qualified names; resolve them with imports
