# AuraCode — Quick Start Guide

This guide walks you from a fresh checkout to a working diagram in five minutes.

---

## Overview

AuraCode is a three-stage pipeline: **index** your Java source into a SQLite call graph,
**trace** a call chain from any method, **render** it as a Mermaid sequence diagram.

| # | Step | What you do | Output |
|---|------|-------------|--------|
| 0 | [Prerequisites](#prerequisites) | Install Java 17+, optional SQLite CLI | — |
| 0 | [Get the JAR](#get-the-jar) | Build from source or copy pre-built JAR | `target/auracode.jar` |
| 0 | [Shell alias](#set-up-a-shell-alias) | `alias ac='java -jar ...'` | `ac` command available |
| 0 | [Config file](#create-a-project-config-file) | Add `.auracode` at your project root | Implicit `--source` and `--db` defaults |
| 1 | [Index](#step-1--index-your-project) | `ac index` | `.auracode.db` call-graph database |
| 2 | [Trace](#step-2--trace-a-call-chain) | `ac trace --entry <fqn>` | Edge list (stdout or file) |
| 3 | [Render](#step-3--render-a-mermaid-diagram) | `ac render` | Mermaid `sequenceDiagram` block |

**Once set up, the everyday workflow is:**

```bash
ac index
ac trace --entry "com.example.SomeClass#someMethod(ArgType)" | ac render --output diagram.md
```

---

## Prerequisites

| Requirement | Version | Notes |
|-------------|---------|-------|
| Java runtime | 17+ | Required to run the JAR |
| SQLite CLI | any | Optional — useful for inspecting the database directly |
| Java source tree | Java 8+ | The project you want to analyse |

### Install SQLite (optional but recommended)

**macOS (Homebrew):**

```bash
brew install sqlite
```

**Ubuntu / Debian:**

```bash
sudo apt install sqlite3
```

**Windows (winget):**

```bash
winget install SQLite.SQLite
```

---

## Get the JAR

Build from source (requires Maven 3.8+):

```bash
./mvnw clean package -DskipTests
# Produces: target/auracode.jar
```

Or copy a pre-built `auracode.jar` to a directory on your path (e.g. `~/bin/`).

---

## Set Up a Shell Alias

Typing `java -jar /path/to/auracode.jar` every time is tedious. Add an alias once:

**bash / zsh** — add to `~/.bashrc` or `~/.zshrc`:

```bash
alias ac='java -jar /path/to/auracode.jar'
```

**Fish** — add to `~/.config/fish/config.fish`:

```fish
alias ac='java -jar /path/to/auracode.jar'
```

Reload your shell or `source ~/.bashrc`, then verify:

```bash
ac --version
```

All examples below use the `ac` alias.

---

## Create a Project Config File

Place a `.auracode` file at the root of the Java project you want to analyse.
This file sets defaults so you do not have to repeat `--source` and `--db` on every command.

**Minimal config (recommended starting point):**

```properties
# .auracode — project defaults for auracode
# CLI flags always override these values.

index.source=src/main/java
db=.auracode.db
```

With this config in place, `ac index` needs no extra flags and `ac trace` / `ac render`
pick up `--db` automatically.

**Extended example** (uncomment what you need):

```properties
# .auracode — project defaults for auracode
# CLI flags always override these values.
# Scoped key (index.db) takes precedence over bare key (db) for that command.

# Shared default database path
db=.auracode.db

# index: make --source implicit
index.source=src/main/java

# index: include Java SDK and third-party call edges (default: false = filtered out)
# index.include-external=false

# trace: cap traversal depth and default output file
# trace.depth=30
# trace.output=trace.txt

# render: default output file
# render.output=diagram.md
```

---

## Step 1 — Index Your Project

Navigate to your project root (where `.auracode` lives) and run:

```bash
ac index
```

With the config above this is equivalent to:

```bash
ac index --source src/main/java --db .auracode.db
```

Expected output:

```log
INFO  Indexing /your/project/src/main/java → /your/project/.auracode.db
INFO  Indexed N methods, M edges.
```

**Force a clean re-index** (e.g. after a large refactor):

```bash
ac index --clean --yes
```

---

## Step 2 — Trace a Call Chain

Pick a method FQN (fully-qualified name) as your entry point:

```plain
<package>.<ClassName>#<methodName>(<ParamType>)
```

**Forward trace** — what does this method call?

```bash
ac trace --entry "com.example.controller.UserController#getUser(Long)"
```

**Save to a file** for later rendering:

```bash
ac trace --entry "com.example.controller.UserController#getUser(Long)" \
         --output trace.txt
```

**Inverse trace** — who calls this method?

```bash
ac trace --entry "com.example.mapper.UserMapper#selectById(Long)" \
         --callers
```

**Inverse trace split by root caller:**

```bash
ac trace --entry "com.example.mapper.UserMapper#selectById(Long)" \
         --callers --split --output trace.txt
```

---

## Step 3 — Render a Mermaid Diagram

**One-shot pipeline** (trace → diagram in a single command):

```bash
ac trace --entry "com.example.controller.UserController#getUser(Long)" \
  | ac render --output diagram.md
```

**From a saved trace file:**

```bash
ac render --input trace.txt --output diagram.md
```

Open `diagram.md` in any Markdown viewer that supports Mermaid (GitHub, VS Code with
the Mermaid extension, Obsidian, etc.) to see the rendered sequence diagram.

---

## Putting It All Together

**Full re-index and diagram from scratch:**

```bash
cd /your/project
ac index --clean --yes
ac trace --entry "com.example.controller.UserController#getUser(Long)" \
  | ac render --output diagram.md
```

**Explore callers of a mapper method, split by root, render multi-section diagram:**

```bash
ac trace --entry "com.example.mapper.UserMapper#selectById(Long)" \
         --callers --split \
  | ac render --output callers-diagram.md
```

**Limit depth on a large graph:**

```bash
ac trace --entry "com.example.service.OrderService#processOrder(Order)" \
         --depth 5 \
  | ac render --output shallow.md
```

---

## Inspecting the Database (optional)

The SQLite database is a plain file you can query directly:

```bash
# List all indexed method FQNs
sqlite3 .auracode.db "SELECT fqn FROM method_node ORDER BY fqn;"

# Count call edges
sqlite3 .auracode.db "SELECT COUNT(*) FROM call_edge;"

# Show direct callees of a method
sqlite3 .auracode.db "
  SELECT callee.fqn
  FROM call_edge e
  JOIN method_node caller ON caller.id = e.caller_id
  JOIN method_node callee ON callee.id = e.callee_id
  WHERE caller.fqn = 'com.example.controller.UserController#getUser(Long)';"
```

---

## Exit Codes

| Code | Meaning |
|------|---------|
| `0` | Success |
| `1` | Usage error (unknown option, missing required argument) |
| `2` | Application error (database not found, entry method not indexed, I/O failure) |

---

## Next Steps

- `USAGE.md` — full CLI reference (all options, FQN format, output formats)
- `ROADMAP.md` — upcoming features and known limitations
- `docs/features/` — per-feature design specs
- `docs/adr/` — architectural decisions (why JavaParser, SQLite, Mermaid, Picocli)
