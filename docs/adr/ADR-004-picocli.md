# ADR-004: Use Picocli as CLI Framework

- **Status:** Accepted
- **Date:** 2026-04-13
- **Deciders:** Project bootstrap

---

## Context

AuraCode is a standalone CLI tool. It needs a framework for argument parsing, subcommand dispatch,
`--help` generation, and shell completion. The tool must run without Spring Boot.

Candidates evaluated:

| Library | Annotation-driven | Subcommands | GraalVM-ready | Spring Boot required |
|---------|------------------|-------------|---------------|----------------------|
| **Picocli** | Yes | Yes | Yes (codegen) | No |
| **Apache Commons CLI** | No | No | N/A | No |
| **JCommander** | Yes | Yes | Partial | No |
| **Spring Shell** | Yes | Yes | Partial | Yes |
| **args4j** | Yes | No | N/A | No |

---

## Decision

Use **`info.picocli:picocli`** (v4.7.5) with the `picocli-codegen` annotation processor.

---

## Rationale

1. **Annotation-driven.** `@Command`, `@Option`, `@Parameters` keep CLI metadata co-located with the
   code — consistent with the project's *Explicit > Magic* principle (metadata is visible, not inferred).

2. **First-class subcommand support.** Phase 1 introduces `index`, `trace`, and `render` as subcommands.
   Picocli's nested `@Command` hierarchy handles this cleanly.

3. **GraalVM native-image ready.** The `picocli-codegen` annotation processor generates
   `reflect-config.json` at compile time, enabling future native binary distribution (Phase 3.4)
   without manual reflection configuration.

4. **No Spring Boot dependency.** AuraCode is intentionally lean. Picocli has zero mandatory
   transitive dependencies — it is a single JAR.

5. **Rich built-in help.** Auto-generates ANSI-coloured `--help` and `--version` output from
   annotations, consistent across all subcommands, with no boilerplate.

6. **Shell completion generation.** `picocli-shell-jline3` (addable later) can generate Bash/Zsh
   completion scripts from the same annotation metadata.

---

## Consequences

- The `picocli-codegen` annotation processor is added to `maven-compiler-plugin` configuration
  (see `pom.xml`). This is a compile-time-only dependency and does not affect the runtime JAR.
- Spring Boot injection patterns (`@Autowired`) are not used in CLI command classes; Picocli
  handles instantiation. Service-layer classes still follow the `Service` / `DefaultService` pattern.
