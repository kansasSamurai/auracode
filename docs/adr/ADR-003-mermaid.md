# ADR-003: Use Mermaid for Diagram Output

- **Status:** Accepted
- **Date:** 2026-04-13
- **Deciders:** Project bootstrap

---

## Context

SourceLens's primary output is a sequence diagram representing a call chain. A diagram description
language must be chosen. The tool must not require a Java-based renderer, since the output should be
consumable by developers in their editor, CI pipeline, or documentation site.

Candidates evaluated:

| Option | License | Rendering | IDE / GitHub support |
|--------|---------|-----------|----------------------|
| **Mermaid** | MIT | Browser / CLI (`mmdc`) | Native in GitHub, GitLab, VS Code, Notion |
| **PlantUML** | MIT | Requires Java + Graphviz | Plugin required; `.puml` files |
| **DOT / Graphviz** | EPL | Requires Graphviz binary | Limited native support |
| **ASCII art** | N/A | None needed | Universal but unreadable at scale |

---

## Decision

Emit **Mermaid** `sequenceDiagram` syntax as the primary output format.

---

## Rationale

1. **MIT license.** Unambiguous; no copyleft concerns for tool distribution.

2. **No Java renderer required.** Mermaid diagrams render natively in GitHub Markdown, GitLab,
   VS Code (with Markdown Preview Mermaid Support), Notion, and Confluence. The user gets immediate
   value without installing anything extra.

3. **Native GitHub support.** Fenced code blocks with ` ```mermaid ` are rendered inline in GitHub
   READMEs and PR comments — the most common place developers review architecture.

4. **Lightweight output.** SourceLens emits a plain text string. No binary generation, no external
   process invocation.

5. **Extensible.** If DOT/JSON output is added later (Phase 2.4), Mermaid remains the default and
   the `--format` flag selects alternatives.

---

## Consequences

- PlantUML's richer diagram types (component diagrams, class diagrams) are not available by default,
  but are not needed for the MVP call-chain use case.
- Mermaid `sequenceDiagram` syntax has limits on node name characters; the renderer must sanitise
  fully-qualified Java method names (replace `.`, `#`, `(`, `)`, `,` with safe equivalents).
