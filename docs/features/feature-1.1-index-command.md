# Feature 1.1 — `index` Command

## Overview

The `index` command is the foundation of AuraCode. It walks a Java source tree, parses every
`.java` file with JavaParser, extracts method→method call edges, and persists them to a SQLite
call-graph database. Subsequent `trace` (1.2) and `render` (1.3) commands are blocked on this.

## CLI

```
auracode index --source <path> [--db <path>]
```

| Option | Short | Required | Default | Description |
|--------|-------|----------|---------|-------------|
| `--source` | `-s` | yes | — | Root directory of Java source to index |
| `--db` | `-d` | no | `.auracode.db` | SQLite output file path |

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

Examples:

| Class type | FQN |
|------------|-----|
| Top-level class | `com.example.service.UserServiceImpl#findById(Long)` |
| Named nested class | `com.example.util.UserSorter$ByUsername#compare(User, User)` |
| Anonymous class | `com.example.util.UserSorter$anonymous:22#compare(User, User)` |

Outer class names and anonymous class markers are separated by `$`, matching Java's class-file
naming convention. Anonymous classes include the source line number for disambiguation.

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
| ~~DEBT-001~~ | ~~Nested/anonymous class FQNs are incorrect~~ — **resolved** |
| DEBT-002 | External-library callee FQNs degrade to `scope#method(?)` — no impact when tracing within own project source. Edge case: library callback/template patterns (e.g. `JdbcTemplate` + `RowMapper`) that call back into user code will break the chain. Deferred to Phase 2. |
| ~~DEBT-003~~ | ~~`CallGraphDb` has no connection pooling~~ — **not applicable**. Pooling solves concurrent thread contention in long-running processes; AuraCode is neither. The JVM starts, one command executes, the process exits. The `try-with-resources` `AutoCloseable` pattern is the correct design. Closed as a deliberate decision. |
| ~~DEBT-004~~ | ~~`IndexCommand` input validation uses plain `if`~~ — **resolved** (`Assert` utility introduced) |

---

## Revision History

### Feature 1.1r1 — `--clean` / `--yes` flags (2026-04-18)

Added two new options to the `index` command:

| Option | Short | Default | Description |
|--------|-------|---------|-------------|
| `--clean` | | false | Delete the existing database before indexing. Prompts for confirmation unless `--yes` is also given. |
| `--yes` | `-y` | false | Skip the `--clean` confirmation prompt. Intended for scripting and CI environments. |

**Design notes:**
- Confirmation uses `System.console().readLine()`. When no console is attached (e.g. CI, piped input), the method returns `false` and logs a warning directing the user to `--yes` — safe default.
- `--yes` without `--clean` is silently ignored.
- `Files.deleteIfExists` is used so a missing database is not an error.

---

## Verification

```bash
# Build
sdk env && ./mvnw clean package

# Self-index
java -jar target/auracode.jar index --source src/main/java --db /tmp/self.db
sqlite3 /tmp/self.db "SELECT fqn FROM method_node;"
sqlite3 /tmp/self.db "SELECT COUNT(*) FROM call_edge;"

# Index MyBatis fixture
java -jar target/auracode.jar index \
  --source test-fixtures/mybatis-sample/src \
  --db /tmp/mybatis.db

# Verify call chain
sqlite3 /tmp/mybatis.db \
  "SELECT c.fqn AS caller, e.fqn AS callee
   FROM call_edge x
   JOIN method_node c ON c.id = x.caller_id
   JOIN method_node e ON e.id = x.callee_id;"
```
