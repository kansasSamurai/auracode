# Feature 2.3r1 — Hardening: Class Hierarchy & Interface Dispatch

## Overview

Feature 2.3 shipped with two LIKE-based prototype heuristics that bridged interface
dispatch by suffix-matching method signatures against all FQNs in the DB.  This revision
replaces both heuristics with hierarchy-aware SQL queries backed by two new tables that
record the `implements`/`extends` relationships discovered during indexing.

**DEBT-007 and DEBT-010 are resolved by this feature.**

---

## Problem with the Prototype Heuristics

The heuristics worked only because the mybatis-sample fixture had one implementation per
interface.  Given a callee FQN `com.example.service.UserService#findById(Long)`:

```sql
-- Old heuristic (DEBT-007): suffix match
WHERE fqn LIKE '%#findById(Long)'
  AND fqn != 'com.example.service.UserService#findById(Long)'
  AND EXISTS (SELECT 1 FROM call_edge ...)
```

If an unrelated `ProductService#findById(Long)` existed (same method name and params,
different hierarchy), the heuristic would return it as a false positive.  Similarly, the
inverse heuristic (DEBT-010) would incorrectly attribute callers of `ProductService`
methods to `UserService` implementations.

---

## Schema Changes

### New tables

```sql
CREATE TABLE class_node (
    id           INTEGER PRIMARY KEY,
    fqn          TEXT NOT NULL UNIQUE,  -- "com.example.service.UserServiceImpl"
    simple_name  TEXT NOT NULL,          -- "UserServiceImpl"
    package_name TEXT NOT NULL,          -- "com.example.service"
    is_interface INTEGER NOT NULL DEFAULT 0  -- 1 = interface/abstract, 0 = concrete
);

CREATE TABLE class_hierarchy (
    child_id  INTEGER NOT NULL REFERENCES class_node(id),
    parent_id INTEGER NOT NULL REFERENCES class_node(id),
    relation  TEXT NOT NULL CHECK(relation IN ('IMPLEMENTS', 'EXTENDS')),
    PRIMARY KEY (child_id, parent_id)
);
```

### New columns on `method_node`

```sql
ALTER TABLE method_node ADD COLUMN class_id    INTEGER REFERENCES class_node(id);
ALTER TABLE method_node ADD COLUMN method_name TEXT;
ALTER TABLE method_node ADD COLUMN params      TEXT;
```

All migrations use the established silent-ignore pattern — existing databases are
automatically upgraded on the next `index` run.

---

## Indexer Changes

A new `ClassHierarchyVisitor` (inner class in `SourceIndexer`) visits every top-level
`ClassOrInterfaceDeclaration`, extracting:

- Class FQN, simple name, package name, and `isInterface()` flag
- `getImplementedTypes()` → IMPLEMENTS edges
- `getExtendedTypes()` → EXTENDS edges

Types are resolved via JavaParser's symbol solver.  Unresolvable types (e.g. external
library supertypes) are silently skipped — only source-internal hierarchy is recorded.
Nested classes are excluded: their parent types are almost always external library types.

`CallEdgeVisitor.visit(MethodDeclaration)` now also populates `class_id`, `method_name`,
and `params` on the method node so that hierarchy queries can match methods across
interface/implementation pairs.

### Persist order in `index()`

```
persistClassNodes    → populates class_node
persistClassHierarchy → populates class_hierarchy (needs class_node)
persistNodes          → populates method_node with class_id (needs class_node)
persistEdges          → populates call_edge
```

---

## Replacement Queries

### `findConcreteCalleeFqns` (forward dispatch)

```sql
SELECT impl_mn.fqn
FROM   method_node   interface_mn
JOIN   class_node    iface_cn    ON iface_cn.id        = interface_mn.class_id
JOIN   class_hierarchy ch         ON ch.parent_id       = iface_cn.id
JOIN   class_node    impl_cn      ON impl_cn.id         = ch.child_id
JOIN   method_node   impl_mn      ON impl_mn.class_id   = impl_cn.id
                               AND impl_mn.method_name  = interface_mn.method_name
                               AND impl_mn.params       = interface_mn.params
WHERE  interface_mn.fqn  = ?
  AND  impl_cn.is_interface = 0
```

### `findInterfaceCallerFqns` (inverse dispatch)

```sql
SELECT DISTINCT caller_mn.fqn
FROM   method_node   impl_mn
JOIN   class_node    impl_cn    ON impl_cn.id         = impl_mn.class_id
JOIN   class_hierarchy ch        ON ch.child_id        = impl_cn.id
JOIN   class_node    iface_cn    ON iface_cn.id        = ch.parent_id
JOIN   method_node   iface_mn    ON iface_mn.class_id  = iface_cn.id
                              AND iface_mn.method_name = impl_mn.method_name
                              AND iface_mn.params      = impl_mn.params
JOIN   call_edge     ce          ON ce.callee_id       = iface_mn.id
JOIN   method_node   caller_mn   ON caller_mn.id       = ce.caller_id
WHERE  impl_mn.fqn = ?
```

---

## Fixture Additions

To make the disambiguation test meaningful, two classes were added to
`test-fixtures/mybatis-sample/src/`:

| File | Purpose |
|------|---------|
| `com/example/service/CachedUserServiceImpl.java` | Second `UserService` impl; no call to `UserMapper` so it does not appear in inverse traces from `UserMapper`. Proves the hierarchy query finds BOTH impls (the old heuristic missed it because it had no outgoing edges). |
| `com/example/product/ProductService.java` | Unrelated interface with its own `findById(Long)`. Proves the hierarchy query does NOT return `ProductServiceImpl` when searching for `UserService` implementations. |
| `com/example/product/ProductServiceImpl.java` | Concrete impl of `ProductService`. |

---

## Behavioral Change: Forward Trace Branches

Before 2.3r1, a forward trace from `UserController#getUser` produced a single path
through `UserServiceImpl`.  After 2.3r1, both `UserService` implementations are found:

```
UserController#getUser(Long) -> UserServiceImpl#findById(Long) : User
UserServiceImpl#findById(Long) -> UserMapper#selectById(Long) : User
UserController#getUser(Long) -> CachedUserServiceImpl#findById(Long) : User
```

The rendered diagram shows both paths.  This is correct behaviour — at the source-code
level, any `UserService` implementation could be injected.

---

## Verification

```bash
./mvnw test
# Expected: all tests pass (previous 20 + 1 new disambiguation test = 21 total)

java -jar target/auracode.jar index \
    --source test-fixtures/mybatis-sample/src --db target/hier-test.db

# Forward trace branches to both UserService implementations
java -jar target/auracode.jar trace \
    --entry "com.example.controller.UserController#getUser(Long)" \
    --db target/hier-test.db

# Inverse trace from UserMapper: CachedUserServiceImpl must NOT appear
java -jar target/auracode.jar trace \
    --entry "com.example.mapper.UserMapper#selectById(Long)" \
    --db target/hier-test.db --callers
```
