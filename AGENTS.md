# Conventions

## Architecture

- Dependency direction is `infrastructure → application → domain` (inward only,
  enforced by `ArchitectureTest`). `OneVsOnePlugin` is the composition root and
  wires all dependencies manually
- application connects to the outside through interfaces in `port/`
- One repository port method is one atomic persistence unit; cross-table
  writes are grouped inside the implementation. Application code never opens
  a transaction itself, and code inside a nested atomic section must not
  catch an inner persistence failure and continue — the unit is rollback-only
  from that point
- `ArenaRegistry` mutators run the required `persist` hook before writing
  back in-memory state, so a strict failure leaves memory and indexes
  untouched. Callers that deliberately persist nothing write `persist = {}`
- `PersistenceFailure` is the only exception type allowed to cross a
  persistence implementation boundary; JDBC, codec, and stored-data
  validation failures are all converted at the seam so lenient callers cannot
  be bypassed
- Use-case results: `*Error?` (null means success) when callers only need the
  rejection reason, or sealed `*Output` types when success itself has multiple
  outcomes or carries data. Result types are declared at the bottom of the
  service file that returns them. Internal binary guards return Boolean and
  lookups return null. Persistence and external-reference failures throw
  `PersistenceFailure`
- Domain state-machine transition results are `*Outcome` types, kept distinct
  from application-level `Output`/`Error` results

## Domain

- Only immutable values
- Domain values with invariants keep their constructor private and are created
  only through companion factory functions (`of`/`new`/`restored`) — types
  without invariants, such as operation results, are exempt. Values the caller
  should not supply (e.g. new ids) are generated inside the factory; rebuilding
  an existing id goes through `restored` or a `new` overload that takes the id
- Stateless predicates purely derived from values may live in domain
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

- Write no comments. The only exception is a single line stating a constraint
  the code cannot express — e.g. imposed by an external system or ordering
  that looks arbitrary. If a comment would describe what the adjacent code
  does, the code is under-named; fix the code instead
- Comments must stand alone for a first-time reader of the file. Never
  mention rejected alternatives, previous behavior, or the change itself
  ("instead of X", "now does Y") — that context belongs in commit messages
  and PR descriptions
- Do not write fully qualified names; resolve them with imports
