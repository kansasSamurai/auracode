# ADR-001: Use JavaParser for AST Parsing

- **Status:** Accepted
- **Date:** 2026-04-13
- **Deciders:** Project bootstrap

---

## Context

SourceLens needs to parse Java source files to extract method declarations, method invocations,
and type information in order to build a call graph. Several libraries exist for this purpose.

Candidates evaluated:

| Library | Approach | Symbol Resolution | License |
|---------|----------|-------------------|---------|
| **JavaParser** | Source-level AST | Yes (Symbol Solver) | Apache 2.0 |
| **Spoon** | Source-level AST + metamodel | Yes | CeCILL-C (LGPL-compatible) |
| **ASM / BCEL** | Bytecode analysis | N/A (no source names) | Apache 2.0 |
| **Eclipse JDT** | Source-level AST | Yes (full ECJ compiler) | EPL 2.0 |

---

## Decision

Use **`com.github.javaparser:javaparser-symbol-solver-core`** (v3.26.2).

---

## Rationale

1. **Lightweight dependency footprint.** JavaParser is a single artifact family. Spoon and Eclipse JDT
   pull in significantly heavier transitive dependencies.

2. **Source-level fidelity.** Unlike bytecode tools (ASM/BCEL), JavaParser works on `.java` source,
   which preserves original names, comments, and line numbers — essential for meaningful diagram output.

3. **Java 8 compatibility via `LanguageLevel`.** `ParserConfiguration.setLanguageLevel(JAVA_8)` allows
   the indexer to parse legacy codebases without needing a Java 8 JDK present at runtime.

4. **Active maintenance.** The 3.x series receives regular releases. JavaParser has a large community
   and comprehensive documentation.

5. **Apache 2.0 license** is unambiguously compatible with commercial and open-source distribution.

---

## Consequences

- We accept that JavaParser's symbol solver requires classpath configuration for full type resolution
  across module boundaries; initial MVP may produce unresolved references for third-party types.
- Eclipse JDT's compiler-grade accuracy is not needed for call-graph extraction; the tradeoff is accepted.
