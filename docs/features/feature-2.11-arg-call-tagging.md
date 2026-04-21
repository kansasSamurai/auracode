# Feature 2.11 — Argument-Call Tagging

## Status
`[x]` Complete

## Problem

JavaParser's `VoidVisitorAdapter` recurses into all child nodes after visiting a
`MethodCallExpr`. When a method call appears as an argument to another call —

```java
userMapper.selectById(user.getId());
```

— the visitor processes `user.getId()` as a child of `selectById(...)` but passes
the **outer caller's** context (`ctx`), attributing the edge to the containing method
rather than to `selectById`. The result is a "ghost sibling" edge:

```
UserServiceImpl#createUser -> UserMapper#selectById(Long)
UserServiceImpl#createUser -> User#getId()        ← ghost: looks like a top-level call
```

In a Mermaid sequence diagram this manifests as `getId()` appearing at the same level
as `selectById()` rather than being visually subordinate to it, creating noise and
misleading diagrams.

## Solution (Option C — Tag, Don't Suppress)

An `is_arg_call INTEGER` column is added to `call_edge`. At index time the visitor
detects whether each `MethodCallExpr` is in argument position and stamps the edge
accordingly. The information is preserved in the database; presentation (trace, render)
decides what to surface.

- **Default behaviour:** `trace` suppresses `is_arg_call = 1` edges — clean output.
- **Opt-in:** `--include-arg-calls` flag on `trace` restores all edges, including
  argument-position calls, for debugging or completeness.
- **No re-index required** to switch between modes.

## Schema Change

```sql
-- call_edge gains one new column
ALTER TABLE call_edge ADD COLUMN is_arg_call INTEGER NOT NULL DEFAULT 0;
```

Existing databases are migrated automatically on the next `index` run via the
`migrateAddColumn` mechanism. Migrated edges receive `is_arg_call = 0` (treated as
non-arg calls), which is the safe default — nothing disappears from existing traces
until re-indexed.

## Detection Logic

The visitor checks whether the `MethodCallExpr` being visited (`n`) is contained in the
**argument list** of its parent `MethodCallExpr`. This correctly distinguishes argument
position from method-chain scope position:

```java
boolean isArgCall = n.getParentNode()
    .map(p -> p instanceof MethodCallExpr mce && mce.getArguments().contains(n))
    .orElse(false);
```

| Source pattern          | `isArgCall` | Reason |
|-------------------------|-------------|--------|
| `f(g())`                | `g` = true  | `g` is in `f`'s argument list |
| `f().g()`               | `f` = false | `f` is the scope of `g`, not an argument |
| `f(obj.g())`            | `obj.g()` = true | the full expression is in `f`'s argument list |
| `f(g().h())`            | `g().h()` = true, `g` = false | `g` is scope of `h`; `g().h()` is arg of `f` |

## Suppression Behaviour

`TraceCommand` gets a new flag:

```bash
--include-arg-calls    Include method calls that appear as arguments to other calls
                       (default: false — arg-position calls are suppressed).
```

`CallGraphDb.getCalleeFqns(String callerFqn, boolean includeArgCalls)` is the
single suppression point. When `includeArgCalls = false`, the SQL appends
`AND e.is_arg_call = 0`. When true, the full set of callees is returned.

`traverseCallers()` (inverse trace) is unaffected — a caller is a caller regardless
of whether it called via an argument expression.

## Edge Array Format

After Feature 2.11 the internal edge `String[]` has five elements:

```java
edge[0] = callerFqn
edge[1] = calleeFqn
edge[2] = callerReturnType
edge[3] = call_sequence   (Feature 2.10)
edge[4] = is_arg_call     "0" or "1"  (Feature 2.11)
```

## Test Coverage

`PipelineIntegrationTest`:

- `argCallTagging_createUser_getIdTaggedAsArgCall` — DB-level: asserts `User#getId()`
  has `is_arg_call = 1`; asserts `setUsername`, `setEmail`, `insert`, `selectById`
  have `is_arg_call = 0`.
- `argCallSuppression_defaultTrace_excludesArgCalls` — trace of `createUser` must
  contain `selectById` and must NOT contain `getId`.
- `argCallSuppression_includeArgCallsFlag_surfacesArgCalls` — same trace with
  `--include-arg-calls`: must contain both `selectById` and `getId`.

Fixture basis: `UserServiceImpl#createUser(String, String)` contains
`userMapper.selectById(user.getId())` where `user.getId()` is the canonical
argument-position call under test.

## Revision History

*(initial release)*

 ---

## Verification

1. User runs ./mvnw test — all existing tests pass, 3 new tests pass.
2. Manual spot-check:

```bash
java -jar target/auracode.jar index --source test-fixtures/mybatis-sample/src --db verify.db

java -jar target/auracode.jar trace \
  --entry "com.example.service.UserServiceImpl#createUser(String, String)" \
  --db verify.db
# getId() must NOT appear

java -jar target/auracode.jar trace \
  --entry "com.example.service.UserServiceImpl#createUser(String, String)" \
  --db verify.db --include-arg-calls
# getId() MUST appear after selectById

rm verify.db
```
