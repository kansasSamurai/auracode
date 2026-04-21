# AuraCode — ROADMAP

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
| 1.1r1 | **`--clean` / `--yes` flags** — delete the existing database before indexing; interactive confirmation prompt with `--yes` bypass for CI | `[x]` | `System.console()` null-guard returns false in non-interactive contexts |
| 1.2 | **`trace` command** — given an entry-point method, query the SQLite call-graph and produce an ordered call chain | `[x]` | Depth-first traversal with cycle detection |
| 1.3 | **`render` command** — convert a call chain to a Mermaid `sequenceDiagram` block and write to stdout or file | `[x]` | Honour ADR-003 (Mermaid over PlantUML) |
| 1.4 | **Integration test** — end-to-end test against a sample Java 8 project fixture | `[x]` | Validates index → trace → render pipeline |

---

## Phase 2: Enhancements

| # | Feature | Status | Notes |
|---|---------|--------|-------|
| 2.1 | **Delta re-indexing** — only re-parse files changed since last index run (use file mtime / SHA) | `[ ]` | Reduces indexing time on large codebases |
| 2.2 | **Exclusion filters** — `--exclude` glob patterns to skip test sources, generated code, etc. | `[ ]` | |
| 2.3 | **Top-down trace mode** — instead of starting from an entry point, trace all callers of a method upward | `[x]` | Inverse call graph; `--callers` flag on `trace`; `--split` splits output by root caller into separate Mermaid blocks |
| 2.3r1 | **Hardening: class hierarchy** — replace LIKE-based interface dispatch heuristics with hierarchy-aware SQL backed by `class_node` + `class_hierarchy` tables; resolves DEBT-007 and DEBT-010 | `[x]` | See `docs/features/feature-2.3r1-hardening-class-hierarchy.md`; forward trace now branches to all concrete implementations |
| 2.4 | **Multiple output formats** — `--format mermaid|dot|json` | `[ ]` | JSON useful for downstream tooling |
| 2.5 | **Config file support** — load default CLI option values from `.sourcelens` (checked first) or `src/etc/sourcelens/config.properties` (fallback) in the project root; CLI flags override file values | `[x]` | `.properties` format; key resolution: `command.option` beats bare `option`; uses Picocli `IDefaultValueProvider`; see `docs/features/feature-2.5-config-file.md`; DEBT-012 defers `~/.sourcelens` user-home fallback |
| 2.6 | **Method return arrows** — emit dashed `-->>` return arrows in Mermaid diagrams labelled with the callee's return type; `void` and unresolvable types are suppressed; return type stored in `method_node.return_type` and embedded in the trace file as an optional ` : ReturnType` suffix | `[x]` | Schema migration is automatic on next `index` run; trace format is backward compatible |
| 2.7 | **Filter external call edges** — by default, suppress edges where the callee class is not in the indexed source tree (Java SDK, third-party libs); `--include-external` flag and `index.include-external` config key opt back in | `[x]` | Filter applied at persist-time using the `allClassNodes` set; DEBT-002 unresolved `(?)` nodes also suppressed by default |
| 2.8 | **Mermaid front-matter header** — prepend a YAML front-matter block to each `sequenceDiagram` so the diagram title and theme are embedded in the output file; title derived from the entry-point method name (or section label in `--split` mode); theme configurable via config file | `[ ]` | Front-matter format: `---\ntitle: AuraCode Trace: [MethodName]\nconfig:\n  theme: forest\n---`; Mermaid ≥ 10 required for front-matter support; `render.theme` config key to override theme |
| 2.9 | **Auto-named `.mmd` output** — `--auto-output` flag on `render` derives the output filename from the entry-point FQN in the trace data and writes a `.mmd` file without requiring an explicit `--output`; in `--split` mode produces one `.mmd` file per section | `[ ]` | Filename derived from first caller FQN in the trace: `ClassName_methodName.mmd` (package stripped, special chars sanitised); `--output` takes precedence when both are supplied; `--auto-output` and stdout are mutually exclusive |
| 2.10 | **Call ordering** — add `call_sequence INTEGER` to `call_edge` so edges are stored in source-code order; `getCalleeFqns` orders by this column; `trace` output and Mermaid diagrams now reflect top-to-bottom call order within each method body | `[x]` | Sequence is 1-based, reset per `MethodDeclaration`; `INSERT OR IGNORE` keeps first-occurrence sequence for repeated calls; existing DBs migrated automatically via `migrateAddColumn`; see `docs/features/feature-2.10-call-ordering.md` |

---

## Phase 3: Advanced

| # | Feature | Status | Notes |
| - | ------- | ------ | ----- |
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
| DEBT-002 | External-library callee FQNs unresolved — only affects calls to 3rd-party libs (not user source); library calls degrade gracefully to `scope#method(?)`; edge case: library callback/template patterns (e.g. JdbcTemplate RowMapper) break the chain; deferred to Phase 2 | 2026-04-13 | deferred |
| DEBT-003 | `CallGraphDb` has no connection pooling — not applicable for a CLI tool; pooling solves concurrent thread contention in long-running processes, which SourceLens is neither; single `try-with-resources` connection is the correct design; closed as deliberate | 2026-04-13 | n/a |
| DEBT-004 | `IndexCommand` input validation uses plain `if` — replace with `Assert` in hardening pass | 2026-04-13 | 2026-04-13 |
| DEBT-005 | No `TraceService` interface — DFS logic is inlined in `TraceCommand`; extract in hardening | 2026-04-13 | — |
| DEBT-006 | `TraceCommand --entry` requires exact FQN match — add substring/fuzzy lookup in hardening | 2026-04-13 | — |
| DEBT-007 | Interface dispatch bridge is a suffix-match heuristic — replace with proper class-hierarchy walk in hardening | 2026-04-13 | 2026-04-18 (Feature 2.3r1) |
| DEBT-008 | `RenderCommand` uses simple class name as participant — two classes with the same simple name in different packages will collide; use aliased FQN in hardening | 2026-04-13 | — |
| DEBT-009 | No `RenderService` interface — rendering logic is inlined in `RenderCommand`; extract in hardening | 2026-04-13 | — |
| DEBT-010 | `findInterfaceCallerFqns` is a suffix-match prototype heuristic — replace with proper class-hierarchy walk in hardening (mirrors DEBT-007 for forward trace) | 2026-04-13 | 2026-04-18 (Feature 2.3r1) |
| DEBT-011 | No config-file mechanism for explicit interface→impl mappings; deferred to hardening — design must cover file format, `interface_mapping` table, load point, and overlap with Feature 3.2 Spring XML bridge; note `.properties` format used by Feature 2.5 cannot express sections, so a format upgrade or separate file may be required | 2026-04-13 | — |
| DEBT-012 | `DefaultConfigProvider` only checks CWD — no `~/.sourcelens` user-home fallback for global user defaults; add as third lowest-priority candidate in hardening | 2026-04-18 | — |

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

*Last updated: 2026-04-20 (Feature 2.10 complete — call ordering)*
