# ADR-002: Use SQLite for Call-Graph Cache

- **Status:** Accepted
- **Date:** 2026-04-13
- **Deciders:** Project bootstrap

---

## Context

After indexing a Java project, AuraCode must persist the call graph so that subsequent `trace`
and `render` commands can query it without re-parsing source files. A storage format must be chosen.

Candidates evaluated:

| Option | Query capability | File portability | Dependencies |
|--------|-----------------|------------------|--------------|
| **SQLite** | Full SQL | Single file | `sqlite-jdbc` |
| **JSON flat files** | Manual scan / jq | Directory tree | None |
| **H2 in-file mode** | Full SQL | Single file | `h2` (large JAR) |
| **Berkeley DB** | Key-value | Single file | Oracle JDBM |

---

## Decision

Use **SQLite** via `org.xerial:sqlite-jdbc` (v3.45.1.0).

---

## Rationale

1. **Queryable.** SQL joins make it trivial to traverse the call graph, find callers of a method,
   or count edges — none of which are possible without parsing the entire dataset in a flat-file approach.

2. **Single-file portability.** The SQLite database is a single `.auracode.db` file in the project
   root, making it easy to inspect with standard tooling (`sqlite3`, DB Browser for SQLite) and to
   `.gitignore`.

3. **Zero external service.** No daemon, no network, no Docker. The JDBC driver bundles the native
   SQLite library, so no separate installation is required.

4. **Minimal JAR weight.** `sqlite-jdbc` (~6 MB with bundled native libs) is lighter than H2 (~2.5 MB
   but with more complex API surface) and far more capable than flat files.

5. **Proven at scale.** SQLite handles hundreds of thousands of rows without issue — more than enough
   for typical Java codebases.

---

## Consequences

- The `.auracode.db` file must be added to `.gitignore` to avoid committing project-specific caches.
- Cross-platform native library bundling is handled automatically by `sqlite-jdbc`; no user action needed.
- Full SQL schema is defined in ROADMAP.md Design Notes and will be formalised in `docs/features/` once
  the `index` command is implemented (Phase 1.1).
