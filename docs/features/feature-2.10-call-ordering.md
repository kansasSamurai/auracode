# Feature 2.10 — Call Ordering

## Status
`[x]` Complete

## Problem

Prior to this feature, the `call_edge` table stored only `(caller_id, callee_id)` pairs
with no sequence information. `CallGraphDb.getCalleeFqns` issued no `ORDER BY`, so SQLite
returned edges in B-tree key order (effectively callee row-insertion order). That order was
incidentally close to source order for simple cases, but:

1. It was never guaranteed — the schema made no ordering promise.
2. On a re-index after edits, B-tree structure could shift subtly.
3. Methods with many callees (3+) frequently surfaced in a visually confusing order
   in both `trace` output and the rendered Mermaid sequence diagram.

## Solution

A `call_sequence` integer column is added to `call_edge`, recording the ordinal position
of each call site within its containing method body (1-based, reset per method declaration).
`getCalleeFqns` now orders by this column, making the traversal output deterministic and
matching source code top-to-bottom read order.

## Schema Change

```sql
-- call_edge gains one new column
ALTER TABLE call_edge ADD COLUMN call_sequence INTEGER NOT NULL DEFAULT 0;
```

Existing databases are migrated automatically on the next `index` run via the existing
`migrateAddColumn` mechanism. Migrated edges receive `call_sequence = 0`; their relative
order is undefined until the source is re-indexed.

## Implementation

### `CallEdgeVisitor` (SourceIndexer.java)

A `private int currentSeq` field is added to the visitor. It is reset to `0` at the start
of every `visit(MethodDeclaration, ...)` call and incremented (pre-increment) each time a
`MethodCallExpr` is visited and an edge is recorded. The sequence value is appended as the
fourth slot of the edge `String[]`:

```
edge[0] = callerFqn
edge[1] = calleeFqn
edge[2] = callerReturnType
edge[3] = call_sequence (String)   ← new
```

### `CallGraphDb`

- `upsertEdge(callerId, calleeId, sequence)` — stores the sequence in the INSERT.
  `INSERT OR IGNORE` semantics are preserved: if the same `(caller_id, callee_id)` pair is
  encountered again (i.e. the caller calls the same method twice), the first occurrence's
  sequence wins and the second is silently ignored.
- `persistEdges` — parses `edge[3]` as an integer (defaults to `0` when absent, preserving
  backward compatibility with any legacy two- or three-element edge arrays).
- `getCalleeFqns` — adds `ORDER BY e.call_sequence` to the query.

## Behaviour Notes

- **Repeated calls:** If `methodA` calls `foo()` at line 10 and again at line 30, only one
  `call_edge` row exists (the graph is a set of edges). The stored sequence reflects the
  first occurrence. This is unchanged from pre-2.10 behaviour.
- **Callers query (`getCallerFqns`):** Inverse traversal does not have a meaningful
  "ordered callers" concept — callers come from different methods entirely — so no `ORDER BY`
  is added there.

## Test Coverage

`PipelineIntegrationTest#callOrderPreserved_createUser_calleesInSourceOrder` —
indexes the fixture, queries `call_edge` directly via JDBC, and asserts that the callees
of `UserServiceImpl#createUser(String, String)` are returned in source-code order:
`setUsername` → `setEmail` → `insert` → `selectById`.

## Revision History

*(initial release)*
