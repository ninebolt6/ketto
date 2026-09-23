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
- application connects to the outside through interfaces in `port/`

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

- Use `kotlin.test` assertions. `ArchitectureTest` prevents direct use of JUnit
  assertions and checks test-layer dependencies
- domain/application tests use pure tests and fakes (via `TestApp`).
  `ArchitectureTest` keeps those packages free of infrastructure, Bukkit/Paper,
  and MockK dependencies
- Infrastructure tests that exercise Bukkit/Paper behavior use MockBukkit.
  `TestEnv` wires real adapters and services, registers listeners on creation,
  and verifies real state. Isolated persistence tests may use `@TempDir` without
  starting a server
- When listener behavior is under test, drive it through a real player action
  or `env.fire(event)`; never invoke handler methods directly, since custom
  HandlerLists require the event's own dispatch path. Scenario setup may use
  `TestEnv` helpers
- Prefer real player actions such as `disconnect()`, `reconnect()`, and
  `teleport()`. Use `PlayerSimulation` for supported action simulations and
  `simulateDamage` with a real `DamageSource.builder` for damage. Construct and
  fire an event fixture only when neither an action nor a simulator exists
- For event-driven progression and recovery behavior, use player actions and
  registered listeners instead of direct calls to application service
  entrypoints. `ArchitectureTest` protects those test boundaries
- Verify fired events with `env.assertFired<T> { }`
- Use MockK only for fault injection or APIs MockBukkit does not implement;
  verify observable behavior through real events and state rather than mock
  interactions
- Test helpers (fakes, fixtures, `TestApp`, `TestEnv`, etc.) go in each layer's
  `fixtures/` subpackage

## Style

- Comments should state intent, constraints, or non-obvious rationale. Prefer
  no comment to boilerplate; avoid facts inferable from names/signatures and
  caller/lifecycle or concrete storage details that can go stale
- Do not write fully qualified names; resolve them with imports
