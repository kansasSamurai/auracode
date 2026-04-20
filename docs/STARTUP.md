# SourceLens — Quick Start Guide

This guide walks you from a fresh checkout to a working diagram in five minutes.

---

## Overview

SourceLens is a three-stage pipeline: **index** your Java source into a SQLite call graph,
**trace** a call chain from any method, **render** it as a Mermaid sequence diagram.

| # | Step | What you do | Output |
|---|------|-------------|--------|
| 0 | [Prerequisites](#prerequisites) | Install Java 17+, optional SQLite CLI | — |
| 0 | [Get the JAR](#get-the-jar) | Build from source or copy pre-built JAR | `target/sourcelens.jar` |
| 0 | [Shell alias](#set-up-a-shell-alias) | `alias sl='java -jar ...'` | `sl` command available |
| 0 | [Config file](#create-a-project-config-file) | Add `.sourcelens` at your project root | Implicit `--source` and `--db` defaults |
| 1 | [Index](#step-1--index-your-project) | `sl index` | `.sourcelens.db` call-graph database |
| 2 | [Trace](#step-2--trace-a-call-chain) | `sl trace --entry <fqn>` | Edge list (stdout or file) |
| 3 | [Render](#step-3--render-a-mermaid-diagram) | `sl render` | Mermaid `sequenceDiagram` block |

**Once set up, the everyday workflow is:**

```bash
sl index
sl trace --entry "com.example.SomeClass#someMethod(ArgType)" | sl render --output diagram.md
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
# Produces: target/sourcelens.jar
```

Or copy a pre-built `sourcelens.jar` to a directory on your path (e.g. `~/bin/`).

---

## Set Up a Shell Alias

Typing `java -jar /path/to/sourcelens.jar` every time is tedious. Add an alias once:

**bash / zsh** — add to `~/.bashrc` or `~/.zshrc`:

```bash
alias sl='java -jar /path/to/sourcelens.jar'
```

**Fish** — add to `~/.config/fish/config.fish`:

```fish
alias sl='java -jar /path/to/sourcelens.jar'
```

Reload your shell or `source ~/.bashrc`, then verify:

```bash
sl --version
```

All examples below use the `sl` alias.

---

## Create a Project Config File

Place a `.sourcelens` file at the root of the Java project you want to analyse.
This file sets defaults so you do not have to repeat `--source` and `--db` on every command.

**Minimal config (recommended starting point):**

```properties
# .sourcelens — project defaults for sourcelens
# CLI flags always override these values.

index.source=src/main/java
db=.sourcelens.db
```

With this config in place, `sl index` needs no extra flags and `sl trace` / `sl render`
pick up `--db` automatically.

**Extended example** (uncomment what you need):

```properties
# .sourcelens — project defaults for sourcelens
# CLI flags always override these values.
# Scoped key (index.db) takes precedence over bare key (db) for that command.

# Shared default database path
db=.sourcelens.db

# index: make --source implicit
index.source=src/main/java

# trace: cap traversal depth and default output file
# trace.depth=30
# trace.output=trace.txt

# render: default output file
# render.output=diagram.md
```

---

## Step 1 — Index Your Project

Navigate to your project root (where `.sourcelens` lives) and run:

```bash
sl index
```

With the config above this is equivalent to:

```bash
sl index --source src/main/java --db .sourcelens.db
```

Expected output:

```log
INFO  Indexing /your/project/src/main/java → /your/project/.sourcelens.db
INFO  Indexed N methods, M edges.
```

**Force a clean re-index** (e.g. after a large refactor):

```bash
sl index --clean --yes
```

---

## Step 2 — Trace a Call Chain

Pick a method FQN (fully-qualified name) as your entry point:

```plain
<package>.<ClassName>#<methodName>(<ParamType>)
```

**Forward trace** — what does this method call?

```bash
sl trace --entry "com.example.controller.UserController#getUser(Long)"
```

**Save to a file** for later rendering:

```bash
sl trace --entry "com.example.controller.UserController#getUser(Long)" \
         --output trace.txt
```

**Inverse trace** — who calls this method?

```bash
sl trace --entry "com.example.mapper.UserMapper#selectById(Long)" \
         --callers
```

**Inverse trace split by root caller:**

```bash
sl trace --entry "com.example.mapper.UserMapper#selectById(Long)" \
         --callers --split --output trace.txt
```

---

## Step 3 — Render a Mermaid Diagram

**One-shot pipeline** (trace → diagram in a single command):

```bash
sl trace --entry "com.example.controller.UserController#getUser(Long)" \
  | sl render --output diagram.md
```

**From a saved trace file:**

```bash
sl render --input trace.txt --output diagram.md
```

Open `diagram.md` in any Markdown viewer that supports Mermaid (GitHub, VS Code with
the Mermaid extension, Obsidian, etc.) to see the rendered sequence diagram.

---

## Putting It All Together

**Full re-index and diagram from scratch:**

```bash
cd /your/project
sl index --clean --yes
sl trace --entry "com.example.controller.UserController#getUser(Long)" \
  | sl render --output diagram.md
```

**Explore callers of a mapper method, split by root, render multi-section diagram:**

```bash
sl trace --entry "com.example.mapper.UserMapper#selectById(Long)" \
         --callers --split \
  | sl render --output callers-diagram.md
```

**Limit depth on a large graph:**

```bash
sl trace --entry "com.example.service.OrderService#processOrder(Order)" \
         --depth 5 \
  | sl render --output shallow.md
```

---

## Inspecting the Database (optional)

The SQLite database is a plain file you can query directly:

```bash
# List all indexed method FQNs
sqlite3 .sourcelens.db "SELECT fqn FROM method_node ORDER BY fqn;"

# Count call edges
sqlite3 .sourcelens.db "SELECT COUNT(*) FROM call_edge;"

# Show direct callees of a method
sqlite3 .sourcelens.db "
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
