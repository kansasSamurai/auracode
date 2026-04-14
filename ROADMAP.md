# SourceLens — ROADMAP

> **Anchor rule:** Before starting any new feature or ADR, check the **Anchor Items** section below.
> Resolve all blockers listed there first.

---

## Anchor Items

*Items here are blockers or high-priority debt that must be resolved before unrelated work proceeds.*

| ID | Description | Status |
|----|-------------|--------|
| — | *(no anchors yet — will be populated as debt accrues)* | — |

---

## Phase 1: Foundation (MVP)

**Goal:** A working CLI that can index a Java project, trace a call chain, and emit a Mermaid diagram.

| # | Feature | Status | Notes |
|---|---------|--------|-------|
| 1.1 | **`index` command** — walk a source tree, parse Java files with JavaParser, persist method-call edges to SQLite | `[x]` | Use `LanguageLevel.JAVA_8` for legacy project compatibility |
| 1.2 | **`trace` command** — given an entry-point method, query the SQLite call-graph and produce an ordered call chain | `[x]` | Depth-first traversal with cycle detection |
| 1.3 | **`render` command** — convert a call chain to a Mermaid `sequenceDiagram` block and write to stdout or file | `[ ]` | Honour ADR-003 (Mermaid over PlantUML) |
| 1.4 | **Integration test** — end-to-end test against a sample Java 8 project fixture | `[ ]` | Validates index → trace → render pipeline |

---

## Phase 2: Enhancements

| # | Feature | Status | Notes |
|---|---------|--------|-------|
| 2.1 | **Delta re-indexing** — only re-parse files changed since last index run (use file mtime / SHA) | `[ ]` | Reduces indexing time on large codebases |
| 2.2 | **Exclusion filters** — `--exclude` glob patterns to skip test sources, generated code, etc. | `[ ]` | |
| 2.3 | **Top-down trace mode** — instead of starting from an entry point, trace all callers of a method upward | `[ ]` | Inverse call graph |
| 2.4 | **Multiple output formats** — `--format mermaid|dot|json` | `[ ]` | JSON useful for downstream tooling |

---

## Phase 3: Advanced

| # | Feature | Status | Notes |
|---|---------|--------|-------|
| 3.1 | **MyBatis XML resolution** — resolve mapper interface calls through `*Mapper.xml` SQL statements | `[ ]` | Requires XML parsing layer on top of JavaParser |
| 3.2 | **Spring XML bridge** — resolve `<bean>` wiring in legacy Spring XML configs | `[ ]` | Low priority; most modern projects use annotations |
| 3.3 | **AI-enhanced mode** — optionally call an LLM to annotate the diagram with plain-language summaries | `[ ]` | Optional feature; requires API key config |
| 3.4 | **GraalVM native image** — produce a native binary with `picocli-codegen` reflect-config | `[ ]` | Picocli annotation processor already wired (see pom.xml) |

---

## Technical Debt Ledger

*Log all `[DEBT]` items here. Format: `[DEBT-NNN] Description — created YYYY-MM-DD`*

| ID | Description | Created | Resolved |
|----|-------------|---------|----------|
| DEBT-001 | Nested/anonymous class FQNs incorrect — caller FQN derived from top-level class only | 2026-04-13 | 2026-04-13 |
| DEBT-002 | External-library callee FQNs unresolved — `JavaParserTypeSolver` only covers source root | 2026-04-13 | — |
| DEBT-003 | `CallGraphDb` has no connection pooling — single connection, single-threaded only | 2026-04-13 | — |
| DEBT-004 | `IndexCommand` input validation uses plain `if` — replace with `Assert` in hardening pass | 2026-04-13 | — |
| DEBT-005 | No `TraceService` interface — DFS logic is inlined in `TraceCommand`; extract in hardening | 2026-04-13 | — |
| DEBT-006 | `TraceCommand --entry` requires exact FQN match — add substring/fuzzy lookup in hardening | 2026-04-13 | — |
| DEBT-007 | Interface dispatch bridge is a suffix-match heuristic — replace with proper class-hierarchy walk in hardening | 2026-04-13 | — |

---

## Design Notes

### Java 8 Parse Compatibility

The tool itself runs on **Java 17** (required runtime), but must parse **Java 8** source trees when indexing
legacy projects. This is achieved by configuring JavaParser at indexer initialisation time:

```java
ParserConfiguration config = new ParserConfiguration();
config.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_8);
StaticJavaParser.setConfiguration(config);
```

See ADR-001 for the full rationale for choosing JavaParser.

### Call-Graph Schema (SQLite)

Provisional schema (to be formalised in a feature doc under `docs/features/`):

```sql
CREATE TABLE method_node (
    id        INTEGER PRIMARY KEY,
    fqn       TEXT NOT NULL UNIQUE   -- e.g. com.example.Foo#bar(String)
);

CREATE TABLE call_edge (
    caller_id INTEGER NOT NULL REFERENCES method_node(id),
    callee_id INTEGER NOT NULL REFERENCES method_node(id),
    PRIMARY KEY (caller_id, callee_id)
);
```

### Fat JAR Distribution

`maven-shade-plugin` merges all dependencies into `target/sourcelens.jar`. The JAR is self-contained
and can be dropped anywhere that has a Java 17+ runtime — no classpath setup needed.

---

*Last updated: 2026-04-13*
