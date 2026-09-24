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

Audit a layered test suite (e.g. unit / service / framework-integration) and produce a plan that pushes coverage down the pyramid: pure logic lives in the cheapest layer, integration tests exist only for what cannot be verified below.

## Ground rules

- **Never judge a test by its name or file location.** Read the test body AND the fixtures/helpers it calls before classifying it. A test inside an integration harness is not an integration test.
- Every classification claim needs evidence: file path + line, and the specific covering test (by name) when claiming duplication.
- Read the covering test yourself. "Same scenario name" is not proof of coverage — compare the asserted behavior and the exercised code path.

## Step 1 — Layer model and inventory

1. Identify the test layers (from directory layout, fixtures, and architecture docs). For each layer, write down what _only_ that layer can verify. Typical:
   - **Pure/unit**: logic on values, predicates, state machines — no framework.
   - **Service/orchestration**: port calls, result mapping, scheduling decisions — via fakes.
   - **Integration/framework**: real event dispatch, real DI/command registration, real DB, real scheduler, real framework-observable state.
2. Count tests per layer. An inverted or flat pyramid (integration ≥ lower layers combined) is a smell to report.
3. List every test function per file (`grep` for test annotations).

## Step 2 — Trace the real execution path (the step most audits skip)

For each integration-layer test, trace what production code it actually executes:

- **Fixture-bypass trap**: if a helper calls the service/manager directly instead of dispatching the command/event, the test re-runs orchestration logic in an expensive harness. It is a duplicate of a service-layer test _regardless of where it lives_.
- **Fixture-assertion trap**: if an assertion checks a mapping/translation implemented inside the fixture (not production code), the test verifies test code — worthless.
- **Dead-path trap**: check whether the production branch a test targets is actually reachable in production (e.g. another guard runs first). A test may "cover" code no production path reaches — flag the production code as a side finding, and note the test proves nothing real.
- **Weak-assertion trap**: assertions like `assertNull(x)` that cannot distinguish the intended outcome ("restored to empty") from the broken one ("never ran"). These barely count as coverage.

## Step 3 — Classify duplication

- **A. Strict subset** — same layer; every assertion is contained in another test that also adds more. Also flag tests misplaced in an unrelated file.
- **B. Cross-layer rerun** — a higher-layer test re-verifies logic that has a lower-layer twin. Verify the twin truly covers it (same scenario, equivalent assertions, same code seam).
- **C. Framework re-verification** — N tests exercising the _same_ framework mechanism (parser rejection, a shared wrapper guard, generic syntax errors). Keep 1–2 representatives (one negative + one positive); delete the rest.
- **Per-node vs per-mechanism**: wiring that carries _distinct per-node logic_ (each command's `executes` body, each listener method, each argument type) is NOT duplication — each is distinct wiring. But when the per-node "wiring" is a single shared helper call with no per-node logic (e.g. every leaf wrapping itself in the same `playerOnly` guard, or every leaf inheriting the same parser's trailing-argument rejection), the N checks are C-class mechanism duplication: keep representatives and document the residual risk (a node could lose the shared call undetected) as a boundary call rather than paying one test per node.

## Step 4 — Adversarial verification of every candidate

For each **delete** candidate, answer with evidence:

1. What does it verify that nothing else does? ("irreducible delta")
2. Is that delta a production path or a fixture artifact?
3. Which lower-layer test covers it — cite the test and confirm assertions are equivalent.
4. Sole-coverage check: `grep` for every production branch the test touches (error-enum mappings, fallback `executes`, message mappings). A test may be the _only_ coverage of a branch despite looking redundant — if so, keep it or fold the coverage elsewhere.
5. Reachability: is the targeted branch reachable in production?
6. Does the test actually verify what its name claims?

For each **keep** decision, assign an irreducible category:

- real event/command dispatch wiring (handler registration, custom HandlerLists, event-specific fields)
- per-node command wiring (`executes`/`requires`/permissions) or message mapping through the real message source
- real persistence round-trip through real repositories
- real scheduler / cancellation / async behavior
- real framework-observable state that fakes can't reproduce
- **deliberate regression coverage — never delete these** even if lower layers cover the logic. Identify them: (a) anything the invoking request explicitly names, (b) tests added in recent commits (`git log`/`git log -p --follow` on the test file) that target edge cases — a recently added edge-case test is almost always a deliberate fix for a missed scenario, not incidental duplication

For each **boundary call** (deletion that loses a thin but real delta), say so explicitly rather than hiding it.

## Step 5 — Consolidation mechanics

- When a fixture-bypass test owns unique coverage, first check whether it can be **upgraded to real dispatch** (e.g. a state where the command is allowed lets a fixture bypass become a real dispatch test). Upgrading beats folding, which beats deleting.
- **Fold-down**: when a doomed integration test's only unique value is logic coverage, propose adding the missing case to the lower-layer suite (a new cheap test) instead of keeping the expensive one — this is how the pyramid actually re-forms.
- Otherwise prefer deletion over merging. When a doomed test owns small unique assertions, **fold** them into the surviving representative (specify exactly which lines) — and prefer fold targets reached through real dispatch over fixture-mediated calls.
- Before calling an assertion undetectable or a branch dead, trace it: replace the guard mentally — would the assertion still pass? If any realistic regression is caught, it is real coverage, not a weak assertion.
- When a deleted test was _named_ for a mechanism it never verified, **strengthen** a survivor by adding the real verification — this turns the audit into net-new coverage.
- **Trim** enumerated cases inside a surviving test when all items hit the same code branch; keep items hitting distinct branches.
- Plan for collateral cleanup: unused imports after deletions.

## Step 6 — Output format

Produce a plan containing:

1. Layer counts before and projected after.
2. Per-file table: every test → action (delete / keep / fold-in / trim / strengthen) → evidence (covering test name, or irreducible category).
3. Explicit justification list of kept integration tests by irreducible category.
4. Post-change invariants: every listener handler / command leaf / production branch retains ≥1 exercising test. List the checks you will run.
5. **Side findings** — first-class output, not an afterthought: uncovered production branches exposed by the audit, mislabeled tests (name claims X but the path exercised is Y), dead/unreachable production code, and vacuous assertions that should be fixed rather than trusted.
6. Risks and boundary calls.
7. Verification commands (build, lint, full test suite).

## Step 7 — Self-review

Re-read the delete list adversarially: for each item, construct the strongest argument _for keeping it_. If you cannot refute it, move the item to keep or mark it as a documented boundary call. Then re-check the keep list once: does each have an irreducible category, or is it just comfortable?
