# CLAUDE.md: Project Onboarding & Standards

## Project Identity

- **Context:** Senior Java Architect (30+ years exp).
- **Core Principle:** High-craft software engineering. Explicit > Magic.
- **Tech Stack:** Java 17+, Maven (no Spring Boot — lean Picocli CLI).

## Architectural Principles

- **Decoupling:** Every service MUST have an `interface` and a `DefaultImpl`.
- **Injection:** Use `@Autowired` on fields.
- **Fail-Fast:** Validate inputs at boundaries using `Assert` or custom exceptions.
- **Documentation:** The filesystem is the Source of Truth, not the chat history.

## Workflow Protocols

### 1. Session Initiation

At the start of every session, Claude must ask:

- "Which **Feature** or **ADR** are we advancing?"
- "Are we in **Prototype (Speed)** or **Hardening (Craft)** mode?"
- "Shall I update the documentation skeleton before we touch code?"

### 2. Prototype vs. Hardening

- **Prototype Mode:** Focus on PoC. Use `// TODO: [DEBT]` for shortcuts.
- **Hardening Mode:** Replace placeholders with interfaces, implement fail-fast logic, and move `// TODO`s to the Technical Debt Ledger.

### 3. Documentation Duty

- **Feature Docs:** Every new feature requires a file in `/docs/features/`.
- **ADRs:** Use ADRs (Architectural Decision Records) for structural decisions (e.g., choice of bytecode parser). These files should go in `/docs/adr/`.
- **Debt Tracking:** Any technical debt created must be logged with a `[DEBT]` tag.

## Coding Conventions

- **Naming:** `Service` (Interface) -> `DefaultService` (Implementation).
- **JS:** Use ES6 modules where possible; default widget properties to `null`.
- **Swing:** Follow Foundation Framework lifecycle and encapsulation patterns.
- **Logging:** Use SLF4J. Log business-significant events, not just errors.

## Common Commands

- **Build:** `./mvnw clean install`
- **Test:** `./mvnw test`
- **Run CLI:** `./mvnw exec:java -Dexec.mainClass="com.sourcelens.Main"`

## Technical Debt Ledger (Reference ROADMAP.md)

- Always check `ROADMAP.md` for "Anchor" items before starting new work.
