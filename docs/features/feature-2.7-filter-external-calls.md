# Feature 2.7 — Filter External Call Edges

## Overview

When AuraCode indexes a real project, `CallEdgeVisitor` emits an edge for every
method call in the source tree — including calls into the Java SDK (`java.util.*`,
`java.lang.*`, etc.) and any third-party library on the classpath.

Before this feature those edges were persisted, polluting `method_node` with hundreds
of low-value entries (no `return_type`, no `class_id`, no hierarchy) and cluttering
traces and diagrams with library internals.

Feature 2.7 suppresses external edges **by default**.  A `--include-external` flag
(and matching `.auracode` config key) restores the previous behaviour when the full
picture is needed.

---

## CLI

New option on the `index` command:

| Option | Default | Description |
|--------|---------|-------------|
| `--include-external` | `false` | Include call edges to Java SDK and third-party libraries. When false (default), only edges where both caller and callee are in the indexed source tree are persisted. |

**Config file key:** `index.include-external=true`

---

## Design

### Why filter at persist-time

At AST resolution time there is no reliable way to distinguish a successfully-resolved
external call (e.g. `java.util.List#add(Object)`) from a successfully-resolved internal
call — JavaParser's symbol resolver handles both transparently.

At persist-time, however, `allClassNodes` already contains every class discovered in
the source tree.  Any callee whose class portion (everything before `#`) is absent from
that set is, by definition, external.  The filter is O(1) per edge (hash-set lookup)
with no extra SQL queries.

### What is filtered

Two categories of external noise are suppressed by the default filter:

1. **Resolved external calls** — callee FQN resolves to a class not in the source tree,
   e.g. `java.util.HashMap#put(Object, Object)`.  The class `java.util.HashMap` is not
   in `allClassNodes`.

2. **Unresolved calls (DEBT-002 fallback)** — callee FQN has the form `scope#method(?)`,
   where `scope` is the raw scope expression (e.g. a variable name, not a FQN).  Such
   strings never match an internal class FQN, so they are filtered automatically.

### Implementation

**`CallGraphDb.persistEdges`** signature changed to:

```java
public void persistEdges(List<String[]> edges, Set<String> internalClassFqns)
```

When `internalClassFqns` is non-null, each edge is checked:

```java
int hash = calleeFqn.indexOf('#');
String calleeClass = hash >= 0 ? calleeFqn.substring(0, hash) : calleeFqn;
if (!internalClassFqns.contains(calleeClass)) continue;
```

Skipping the edge also skips the `upsertNode` call for the callee, preventing orphan
`method_node` rows.

**`SourceIndexer.index()`** builds the filter set:

```java
Set<String> internalClassFqns = includeExternal
        ? null
        : allClassNodes.stream().map(n -> n[0]).collect(Collectors.toSet());
db.persistEdges(allEdges, internalClassFqns);
```

`null` → no filtering (all edges persisted, preserving pre-2.7 behaviour).

---

## Verification

```bash
./mvnw test
# Expected: all tests pass (21 existing + 2 new filter assertions = 23 total)

# Default mode — zero external nodes
java -jar target/auracode.jar index \
    --source test-fixtures/mybatis-sample/src --db target/filter-test.db
sqlite3 target/filter-test.db \
    "SELECT COUNT(*) FROM method_node WHERE fqn LIKE 'java.%';"
# Expected: 0

sqlite3 target/filter-test.db \
    "SELECT COUNT(*) FROM method_node WHERE fqn LIKE '%(?)';"
# Expected: 0

# --include-external — external nodes present
java -jar target/auracode.jar index \
    --source test-fixtures/mybatis-sample/src \
    --db target/filter-inc-test.db \
    --include-external
sqlite3 target/filter-inc-test.db \
    "SELECT COUNT(*) FROM method_node WHERE fqn LIKE 'java.%' OR fqn LIKE '%(?)';"
# Expected: > 0
```
