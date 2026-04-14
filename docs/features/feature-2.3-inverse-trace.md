# Feature 2.3 — Inverse Trace (Top-Down / "Who Calls This?")

## Overview

Feature 2.3 extends the `trace` command with a `--callers` flag that reverses the
traversal direction. Instead of walking *down* from an entry point to its callees,
it walks *up* from a target method to every method that eventually calls it.

**Primary use case:** given a method deep in a call stack (e.g. a DAO query or a
utility helper), instantly answer "who calls this?" — useful for impact analysis,
refactoring preparation, and understanding entry points in an unfamiliar codebase.

The output format is identical to forward trace (`callerFqn -> calleeFqn` per line),
so the `render` command works on inverse trace output without modification.

---

## CLI

```bash
sourcelens trace \
  --entry  "<FQN of target method>" \
  --db     <path/to/graph.db> \
  --callers
```

| Option | Description |
|--------|-------------|
| `--callers` | Inverse mode: trace all callers of `--entry` upward |
| `--entry` | FQN of the *target* method (deepest node you're interested in) |
| `--db` | SQLite database produced by `index` |
| `--output` | Optional file; defaults to stdout |
| `--depth` | Max DFS depth (default: 50) |

---

## Algorithm

Reverse depth-first search: for each visited node, query `call_edge` in the
*reverse* direction (find rows where the node is the *callee*) to retrieve all
direct callers. Cycle detection uses the same `LinkedHashSet<String>` visited
set as forward trace.

**Interface-caller heuristic (DEBT-010):**
The DB may store caller edges against an *interface* FQN (e.g. `UserService#findById`)
rather than the concrete implementation (`UserServiceImpl#findById`). A direct
`getCallerFqns("UserServiceImpl#findById")` would return nothing.

The prototype heuristic `findInterfaceCallerFqns` mirrors the forward-trace
`findConcreteCalleeFqns`: it searches for callers of *any* node sharing the same
`#method(params)` suffix, excluding the original FQN. This bridges the gap for
single-implementation cases.

**DEBT-011 (deferred):** A config-file approach to supply explicit interface→impl
mappings was discussed and deferred to hardening. The suffix heuristic is sufficient
for prototype.

```
traverseCallers(fqn):
    if depth == 0 or fqn already visited → return
    mark fqn visited
    directCallers = getCallerFqns(fqn)
    effectiveCallers = directCallers if non-empty else findInterfaceCallerFqns(fqn)
    for each caller in effectiveCallers:
        emit "caller -> fqn"
        traverseCallers(caller, depth - 1)
```

---

## Output Format

Same line format as forward trace:

```
com.example.service.UserServiceImpl#findById(Long) -> com.example.mapper.UserMapper#selectById(Long)
com.example.controller.UserController#getUser(Long) -> com.example.service.UserServiceImpl#findById(Long)
```

The `render` command consumes this output unchanged to produce a Mermaid
`sequenceDiagram` showing the *upstream* call path.

---

## Known Debt

| ID | Description |
|----|-------------|
| DEBT-010 | `findInterfaceCallerFqns` is a suffix-match prototype heuristic; replace with class-hierarchy walk in hardening |
| DEBT-011 | No config-file mechanism for explicit interface→impl mappings; deferred to hardening (overlaps with Feature 3.2 Spring XML bridge) |

---

## Verification

```bash
# Build
./mvnw clean package

# Re-index the fixture (delete stale DB first if needed)
java -jar target/sourcelens.jar index \
  --source test-fixtures/mybatis-sample/src \
  --db db/mybatis.db

# Inverse trace from the deepest node
java -jar target/sourcelens.jar trace \
  --entry "com.example.mapper.UserMapper#selectById(Long)" \
  --db db/mybatis.db \
  --callers

# Expected stdout (order may vary):
# com.example.service.UserServiceImpl#findById(Long) -> com.example.mapper.UserMapper#selectById(Long)
# com.example.controller.UserController#getUser(Long) -> com.example.service.UserServiceImpl#findById(Long)

# Pipe directly into render to see the upstream diagram
java -jar target/sourcelens.jar trace \
  --entry "com.example.mapper.UserMapper#selectById(Long)" \
  --db db/mybatis.db \
  --callers | \
  java -jar target/sourcelens.jar render

# Run tests
./mvnw test
# Expected: Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
```
