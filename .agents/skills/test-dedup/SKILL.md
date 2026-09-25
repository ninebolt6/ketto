---
name: test-dedup
description: Audit a layered test suite for duplicate, misplaced, or meaningless tests with test-pyramid awareness; produce an evidence-backed deletion/consolidation plan
argument-hint: "[test-source-root]"
allowed-tools:
  - read
  - grep
  - glob
  - exec
---

# Test Suite Deduplication & Pyramid Audit

Produce a plan that pushes coverage down the test pyramid: pure logic in the cheapest layer, integration tests only for what lower layers cannot verify.

## Ground rules

- Never judge a test by its name or file location. Read the test body AND the fixtures/helpers it calls — a test inside an integration harness is not necessarily an integration test.
- Every claim needs evidence: file:line, and the covering test's name when claiming duplication — and read that covering test. Same scenario name is not proof of coverage; compare asserted behavior and the exercised code path.

## Step 1 — Layer model and inventory

- Identify the layers (directory layout, fixtures, architecture docs) and what _only_ each layer can verify. Typically: pure values/state machines (unit), port calls and orchestration via fakes (service), real dispatch/DI/DB/scheduler/framework-observable state (integration).
- Count tests per layer (`grep` the test annotation). Integration ≥ lower layers combined is a smell to report.

## Step 2 — Trace the real execution path

For each integration-layer test, trace what production code actually runs. Four traps, in order of subtlety:

- **Fixture-bypass**: a helper calls the service directly instead of dispatching the command/event. The test re-runs orchestration logic in an expensive harness — a service-layer duplicate regardless of where it lives.
- **Fixture-assertion**: the assertion checks a mapping implemented inside the fixture, not production code. The test verifies test code — worthless.
- **Dead-path**: the production branch the test targets is unreachable (another guard runs first). Flag the production code as a side finding; the test proves nothing real.
- **Weak assertion**: `assertNull(x)` that cannot distinguish "restored to empty" from "never ran" barely counts as coverage.

## Step 3 — Classify duplication

- **A. Strict subset** — same layer; every assertion is contained in a richer test. Also flag tests misplaced in an unrelated file.
- **B. Cross-layer rerun** — re-verifies logic that has a lower-layer twin (confirm same scenario, equivalent assertions, same code seam).
- **C. Mechanism re-verification** — N tests exercising _one shared_ framework mechanism (parser rejection, a shared wrapper guard). Keep 1–2 representatives (one negative, one positive); delete the rest.
- **Per-node vs per-mechanism**: wiring carrying distinct per-node logic (each leaf's `executes` body, each listener method) is NOT duplication. But a shared helper invoked identically per node IS mechanism duplication — keep representatives and record the residual risk (a node silently losing the wrapper) as a boundary call.

## Step 4 — Adversarial verification

For each **delete** candidate, answer with evidence:

1. Its irreducible delta — what does it verify that nothing else does?
2. Is that delta a production path or a fixture artifact?
3. Which lower-layer test covers it — and are the assertions equivalent?
4. Sole-coverage check: `grep` every production branch the test touches (error-enum mappings, fallback `executes`, message arms). A test may be the only coverage of a branch despite looking redundant — if so, keep it or fold the coverage elsewhere.
5. Is the targeted branch reachable in production?
6. Does the test verify what its name claims?

For each **keep**, assign an irreducible category:

- real dispatch wiring (handler registration, custom HandlerLists, event-specific fields)
- per-node command wiring or message mapping through the real message source
- real persistence round-trip
- real scheduler / cancellation / async behavior
- framework-observable state that fakes cannot reproduce
- **deliberate regression coverage — never delete these**: (a) anything the invoking request explicitly names, (b) edge-case tests added by recent commits (`git log -p --follow` the test file — a recently added edge-case test is almost always a deliberate fix for a missed scenario)

For each **boundary call** (deletion that loses a thin but real delta), say so explicitly rather than hiding it.

## Step 5 — Coverage measurement

Regenerate a fresh baseline (a stale report misattributes coverage) before planning deletions, and a second reading after applying them — `./gradlew test koverXmlReport`, report at `build/reports/kover/report.xml`:

- Coverage counts _execution_, not production-path execution: a fixture that replicates a production mapping and invokes it marks those lines "covered" without any real path being exercised. Never use the report alone to call a test redundant or a line safely covered.
- Every production line/branch that loses coverage demands classification: dead/unreachable code (confirms a side finding), fixture-replica-only execution (fake coverage — losing it is correct), or a real path (the plan was wrong — restore or fold).
- Cross-check the declared test count against the runner's results (JUnit XML, test report): skipped/aborted tests and tests the runner rejects contribute zero coverage — report them as dormant-test findings.
- Mine the never-covered list for uncovered production branches and report them as side findings — they are gaps regardless of duplication.

## Step 6 — Consolidation mechanics

Priority order: upgrade a fixture-bypass test to real dispatch > fold-down (add the missing case to the lower-layer suite) > fold assertions into a survivor > delete.

- Prefer fold targets reached through real dispatch.
- Before calling an assertion undetectable or a branch dead, mentally remove the guard — if any realistic regression gets caught, it is real coverage.
- If a deleted test was _named_ for a mechanism it never verified, strengthen a survivor to add that verification — this turns the audit into net-new coverage.
- Trim enumerated cases inside a survivor when all items hit one branch; keep items hitting distinct branches.
- Delete fixture helpers that replicate production message/error mappings — they invite fixture-assertion tests.

## Step 7 — Output

1. Layer counts before and projected after.
2. Per-file table: every test → action (delete / keep / fold-in / trim / strengthen) → evidence.
3. Kept integration tests justified by irreducible category.
4. Post-change invariants: every handler / command leaf / production branch keeps ≥1 exercising test; coverage-delta classification; verification commands (build, lint, full suite).
5. **Side findings** — uncovered production branches, mislabeled tests, dead/unreachable production code, vacuous assertions.
6. Risks and boundary calls.

## Step 8 — Self-review

Re-read the delete list adversarially: for each item, construct the strongest argument _for keeping it_. If you cannot refute it, move the item to keep or mark it a documented boundary call. Then re-check the keep list: does each have an irreducible category, or is it just comfortable?
