# Conventions

## Architecture

- Dependency direction is `infrastructure → application → domain` (inward
  only). Wire dependencies manually in
  `OneVsOnePlugin` (the composition root); application code reaches the
  outside only through interfaces declared in `port/`
- One repository port method is one atomic persistence unit; cross-table
  writes are grouped inside the implementation. Application code never opens
  a transaction itself, and code inside a nested atomic section must not
  catch an inner persistence failure and continue — the unit is rollback-only
  from that point
- `PersistenceFailure` is the only exception type allowed to cross a
  persistence implementation boundary; JDBC, codec, and stored-data
  validation failures are all converted at the seam so lenient callers cannot
  be bypassed
- Use-case results: `*Error?` (null means success) when callers only need the
  rejection reason, or sealed `*Output` types when success itself has multiple
  outcomes or carries data; declare them at the bottom of the service file.
  Binary accept/reject checks return Boolean and
  lookups return null. Persistence and external-reference failures throw
  `PersistenceFailure`. Domain state-machine transition results are `*Outcome`
  types, kept distinct from application-level `Output`/`Error` results

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

- Domain/application tests are pure with fakes via `TestApp`; infrastructure
  tests use MockBukkit via `TestEnv` (listeners self-register on creation).
  Isolated persistence tests may use `@TempDir` without a server
- Drive event-driven behavior through real player actions (`disconnect()`,
  `reconnect()`, `teleport()`), then `PlayerSimulation`/`simulateDamage`
  with a real `DamageSource.builder`, then `env.fire(event)` — in that
  order. Never invoke a listener handler or service entrypoint to produce
  the behavior under test: custom HandlerLists need the event's own
  dispatch path. Setup and assertions may use `TestEnv` helpers and
  service calls
- Verify fired events with `env.assertFired<T> { }`. Use MockK only for fault
  injection or APIs MockBukkit lacks; verify observable state, not mock
  interactions
- Test helpers (fakes, `TestApp`, `TestEnv`, etc.) go in each layer's
  `fixtures/` subpackage
- A test file mirrors the class under test (`Foo` -> `FooTest`, or
  `FooMeaningTest` for splits); scenario flows spanning multiple components
  (E2E) are exempt

## Style

- Formatting is enforced by ktlint (`.editorconfig`); `./gradlew ktlintFormat` fixes violations
- Write no comments or KDoc. The only exception is a single line stating a
  constraint the code cannot express — e.g. imposed by an external system or
  ordering that looks arbitrary. If a comment would describe what the
  adjacent code does, the code is under-named; fix the code instead
- Comments must stand alone for a first-time reader of the file. Never
  mention rejected alternatives, previous behavior, or the change itself
  ("instead of X", "now does Y") — that context belongs in commit messages
  and PR descriptions
- Do not write fully qualified names; resolve them with imports
