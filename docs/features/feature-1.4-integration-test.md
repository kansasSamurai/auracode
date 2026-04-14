# Feature 1.4 — Integration Test

## Overview

Feature 1.4 closes Phase 1 by adding automated tests that validate the full
`index → trace → render` pipeline. It replaces the placeholder `MainTest.java`
smoke test with a meaningful test suite covering:

1. **Unit tests** — pure FQN-parsing helpers in `RenderCommand` (fast, no I/O)
2. **Pipeline integration test** — runs all three commands programmatically
   against the `mybatis-sample` fixture and asserts the final Mermaid output

## How to Run

```bash
./mvnw test
```

Maven Surefire discovers and runs all `*Test.java` classes under `src/test/`.

## Test Classes

| Class | Type | Location |
|-------|------|----------|
| `RenderCommandTest` | Unit | `src/test/java/com/sourcelens/command/` |
| `PipelineIntegrationTest` | Integration | `src/test/java/com/sourcelens/integration/` |

## Coverage Summary

### `RenderCommandTest` (unit)

Tests the FQN → Mermaid mapping helpers in `RenderCommand`:

| Test | Covers |
|------|--------|
| `toParticipant_topLevelClass` | Simple class name extraction |
| `toParticipant_nestedClass` | `$` → `_` substitution |
| `toParticipant_anonymousClass` | `$` and `:` → `_` substitution |
| `toMessage_standardFqn` | Method signature extraction |
| `sanitize_dollarAndColon` | Character replacement |

### `PipelineIntegrationTest` (integration)

Runs the full `index → trace → render` pipeline in a `@TempDir` against
`test-fixtures/mybatis-sample/src` and asserts the Mermaid output contains
the expected participants and call edges for `UserController#getUser(Long)`.

## Fixture

`test-fixtures/mybatis-sample/src` — the MyBatis-layered app used throughout
Phase 1 development. Expected call chain:

```
UserController#getUser(Long) → UserServiceImpl#findById(Long) → UserMapper#selectById(Long)
```

## Verification

```bash
./mvnw test

# Expected output (abbreviated):
# [INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
```
