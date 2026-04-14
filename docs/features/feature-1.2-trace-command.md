# Feature 1.2 — `trace` Command

## Overview

The `trace` command queries the SQLite call-graph populated by Feature 1.1 (`index`). Given
an entry-point method FQN, it performs a depth-first traversal of the call graph and emits an
ordered edge list to stdout (or a file). The output is the input contract for Feature 1.3
(`render`), which converts it to a Mermaid `sequenceDiagram`.

## CLI

```plain
sourcelens trace --entry <fqn> [--db <path>] [--output <path>] [--depth <n>]
```

| Option | Short | Required | Default | Description |
| ------ | ----- | -------- | ------- | ----------- |
| `--entry` | `-e` | yes | — | Fully-qualified entry-point method (e.g. `com.example.Foo#bar()`) |
| `--db` | `-d` | no | `.sourcelens.db` | SQLite database written by `index` |
| `--output` | `-o` | no | stdout | Write edge list to file instead of stdout |
| `--depth` | `-n` | no | `50` | Maximum DFS depth (guards against very deep graphs) |

## Architecture

```plain
TraceCommand          → validates flags, opens CallGraphDb, drives DFS, writes output
CallGraphDb           → nodeExists(), getCalleeFqns() — read-only queries added for trace
```

DFS traversal logic is inlined in `TraceCommand` for prototype speed — see DEBT-005.

## Output Format

One directed edge per line:

```plain
<callerFqn> -> <calleeFqn>
```

Example:

```plain
com.example.controller.UserController#getUser(Long) -> com.example.service.UserServiceImpl#findById(Long)
com.example.service.UserServiceImpl#findById(Long) -> com.example.mapper.UserMapper#selectById(Long)
```

If the entry method has no outgoing edges in the index, output is empty (exit 0).

## Algorithm

```java
visited = new LinkedHashSet<String>()    // insertion-ordered; doubles as cycle detector

traverse(fqn, depth):
  if depth <= 0  → log warn "max depth reached at <fqn>", return
  if fqn in visited → return             // cycle detected, silently skip
  visited.add(fqn)
  for callee in db.getCalleeFqns(fqn):
    emit "<fqn> -> <callee>"
    traverse(callee, depth - 1)
```

Pre-order DFS: a caller edge is emitted before recursing into the callee's subtree.

## New `CallGraphDb` Methods

```java
/** Returns true if a method_node row exists for the given FQN. */
public boolean nodeExists(String fqn)

/** Returns FQNs of all direct callees of the given caller FQN.
 *  Returns an empty list if the FQN is not in the graph or has no outgoing edges. */
public List<String> getCalleeFqns(String callerFqn)
```

SQL for `getCalleeFqns`:

```sql
SELECT n2.fqn
FROM   method_node n1
JOIN   call_edge   e  ON e.caller_id = n1.id
JOIN   method_node n2 ON n2.id = e.callee_id
WHERE  n1.fqn = ?
```

## Known Debt

| ID | Description |
| -- | ----------- |
| DEBT-005 | No `TraceService` interface — DFS logic is inlined in `TraceCommand`; extract in hardening |
| DEBT-006 | `--entry` requires exact FQN match; add substring / fuzzy lookup in hardening |

## Verification

```bash
# Build
./mvnw clean package

# Index the test fixture (skip if db already exists)
java -jar target/sourcelens.jar index --source test-fixtures/mybatis-sample/src --db db/mybatis.db

# Trace from UserController#getUser — happy path
java -jar target/sourcelens.jar trace --entry "com.example.controller.UserController#getUser(Long)" --db db/mybatis.db

# Expected stdout:
# com.example.controller.UserController#getUser(Long) -> com.example.service.UserServiceImpl#findById(Long)
# com.example.service.UserServiceImpl#findById(Long) -> com.example.mapper.UserMapper#selectById(Long)

# Error case — entry not in index
java -jar target/sourcelens.jar trace \
  --entry "com.example.Nonexistent#missing()" \
  --db db/mybatis.db
# Expected: ERROR log "Entry method not found in index: ..." and exit code 1

# Write to file instead of stdout
java -jar target/sourcelens.jar trace \
  --entry "com.example.controller.UserController#getUser(Long)" \
  --db db/mybatis.db \
  --output /tmp/trace.txt
cat /tmp/trace.txt
```
