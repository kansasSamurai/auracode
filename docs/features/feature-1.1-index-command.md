# Feature 1.1 — `index` Command

## Overview

The `index` command is the foundation of SourceLens. It walks a Java source tree, parses every
`.java` file with JavaParser, extracts method→method call edges, and persists them to a SQLite
call-graph database. Subsequent `trace` (1.2) and `render` (1.3) commands are blocked on this.

## CLI

```
sourcelens index --source <path> [--db <path>]
```

| Option | Short | Required | Default | Description |
|--------|-------|----------|---------|-------------|
| `--source` | `-s` | yes | — | Root directory of Java source to index |
| `--db` | `-d` | no | `.sourcelens.db` | SQLite output file path |

## Architecture

```
IndexCommand          → validates flags, delegates to SourceIndexer
SourceIndexer         → configures JavaParser, walks tree, drives CallEdgeVisitor
CallEdgeVisitor       → VoidVisitorAdapter — extracts {callerFqn, calleeFqn} pairs
CallGraphDb           → SQLite schema init, upsertNode, upsertEdge, batch commit
```

## Database Schema

```sql
CREATE TABLE method_node (
    id  INTEGER PRIMARY KEY,
    fqn TEXT NOT NULL UNIQUE   -- e.g. com.example.Foo#bar(String)
);

CREATE TABLE call_edge (
    caller_id INTEGER NOT NULL REFERENCES method_node(id),
    callee_id INTEGER NOT NULL REFERENCES method_node(id),
    PRIMARY KEY (caller_id, callee_id)
);
```

## FQN Format

`<package>.<ClassName>#<methodName>(<paramType1>, <paramType2>)`

Example: `com.example.service.UserServiceImpl#findById(Long)`

## Symbol Resolution

JavaParser's `JavaSymbolSolver` is configured with:
- `ReflectionTypeSolver` — resolves JDK types
- `JavaParserTypeSolver(sourcePath)` — resolves types within the indexed project

On `UnsolvedSymbolException` (external libraries, missing classpath), the callee FQN falls back to:
`<scope>#<methodName>(?)` — see DEBT-002.

## Test Fixture

`test-fixtures/mybatis-sample/` — a minimal Spring Boot + MyBatis layered app:

```
UserController#getUser → UserServiceImpl#findById → UserMapper#selectById
```

## Known Debt

| ID | Description |
|----|-------------|
| DEBT-001 | Nested/anonymous class FQNs are incorrect — caller derived from top-level class only |
| DEBT-002 | External-library callee FQNs unresolved without full classpath |
| DEBT-003 | `CallGraphDb` has no connection pooling — single connection, single-threaded only |
| DEBT-004 | `IndexCommand` input validation uses plain `if` — replace with `Assert` in hardening |

## Verification

```bash
# Build
sdk env && ./mvnw clean package

# Self-index
java -jar target/sourcelens.jar index --source src/main/java --db /tmp/self.db
sqlite3 /tmp/self.db "SELECT fqn FROM method_node;"
sqlite3 /tmp/self.db "SELECT COUNT(*) FROM call_edge;"

# Index MyBatis fixture
java -jar target/sourcelens.jar index \
  --source test-fixtures/mybatis-sample/src \
  --db /tmp/mybatis.db

# Verify call chain
sqlite3 /tmp/mybatis.db \
  "SELECT c.fqn AS caller, e.fqn AS callee
   FROM call_edge x
   JOIN method_node c ON c.id = x.caller_id
   JOIN method_node e ON e.id = x.callee_id;"
```
