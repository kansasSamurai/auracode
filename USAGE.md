# sourcelens(1)

## NAME

**sourcelens** — static call-graph analysis and Mermaid sequence diagram generation for Java projects

## SYNOPSIS

```
sourcelens [--help] [--version] <command> [<args>]
```

```
sourcelens index   --source <dir>  [--db <file>]
sourcelens trace   --entry  <fqn>  [--db <file>] [--output <file>]
                                   [--depth <n>]  [--callers] [--split]
sourcelens render  [--input <file>] [--output <file>]
```

## DESCRIPTION

**sourcelens** parses Java source trees with a static analyser, builds a method-level
call graph, and produces Mermaid `sequenceDiagram` blocks that visualise call chains
and their return types.

The tool is composed of three independent commands that form a pipeline:

```
index  →  trace  →  render
```

Each command reads from and writes to files (or stdin/stdout), so the stages can be
run separately or chained with shell pipes.

## COMMANDS

---

### index

Walk a Java source tree, parse every `.java` file, and persist method-call edges to
a SQLite database.

```
sourcelens index --source <dir> [--db <file>]
```

**Options**

| Option | Short | Default | Description |
|--------|-------|---------|-------------|
| `--source <dir>` | `-s` | *(required)* | Root directory of the Java source tree to index. |
| `--db <file>` | `-d` | `.sourcelens.db` | SQLite output file. Created if it does not exist; updated incrementally if it does. |
| `--help` | `-h` | | Print command help and exit. |

**Notes**

- The tool parses source at **Java 8** language level for maximum compatibility with
  legacy codebases, but requires a **Java 17+** runtime to execute.
- Re-running `index` on the same `--db` is safe: nodes and edges are upserted, not
  duplicated.
- Method return types are stored alongside each node and are used by `trace` to
  annotate the trace file (see Feature 2.6).

---

### trace

Query the call graph and emit an ordered, depth-first edge list from a given entry
point.

```
sourcelens trace --entry <fqn> [--db <file>] [--output <file>]
                               [--depth <n>]  [--callers] [--split]
```

**Options**

| Option | Short | Default | Description |
|--------|-------|---------|-------------|
| `--entry <fqn>` | `-e` | *(required)* | Fully-qualified entry-point method (see *FQN Format* below). |
| `--db <file>` | `-d` | `.sourcelens.db` | SQLite database produced by `index`. |
| `--output <file>` | `-o` | stdout | Write the edge list to a file instead of stdout. |
| `--depth <n>` | `-n` | `50` | Maximum DFS traversal depth. Prevents runaway traversal on cyclic graphs. |
| `--callers` | | false | **Inverse mode.** Trace all callers of `--entry` upward through the call graph instead of tracing callees downward. |
| `--split` | | false | Used with `--callers`. Partition the output into one section per independent root caller, each preceded by a `=== <fqn> ===` header. |
| `--help` | `-h` | | Print command help and exit. |

**Trace file format**

Each line is a directed call edge:

```
com.example.controller.UserController#getUser(Long) -> com.example.service.UserServiceImpl#findById(Long) : User
com.example.service.UserServiceImpl#findById(Long) -> com.example.mapper.UserMapper#selectById(Long) : User
```

The optional ` : ReturnType` suffix carries the callee's return type as stored in the
database. It is omitted when the return type is unknown (e.g. calls into external
libraries). The suffix is backward compatible — `render` treats its absence as
`returnType = null`.

When `--split` is used, sections are separated by header lines:

```
=== com.example.controller.UserController#getUser(Long) ===
com.example.service.UserServiceImpl#findById(Long) -> com.example.mapper.UserMapper#selectById(Long) : User
...
```

---

### render

Convert a trace edge list to one or more fenced Mermaid `sequenceDiagram` blocks.

```
sourcelens render [--input <file>] [--output <file>]
```

**Options**

| Option | Short | Default | Description |
|--------|-------|---------|-------------|
| `--input <file>` | `-i` | stdin | Edge list file produced by `trace`. |
| `--output <file>` | `-o` | stdout | Write the Mermaid diagram to a file. |
| `--help` | `-h` | | Print command help and exit. |

**Output format**

A single-section trace produces one fenced code block:

````
```mermaid
sequenceDiagram
    participant UserController
    participant UserServiceImpl
    participant UserMapper
    UserController->>UserServiceImpl: findById(Long)
    UserServiceImpl->>UserMapper: selectById(Long)
    UserMapper-->>UserServiceImpl: User
    UserServiceImpl-->>UserController: User
```
````

Forward call arrows (`->>`) are emitted in call order.
Dashed return arrows (`-->>`) are emitted in reverse call order, labelled with the
return type.  `void` returns and edges with no resolved return type are silently
suppressed.

A `--split` trace (multi-section input) produces one labelled block per section:

```
## UserController: getUser(Long)

```mermaid
sequenceDiagram
    ...
```

## UserController: createUser(String, String)

```mermaid
sequenceDiagram
    ...
```
```

---

## FQN FORMAT

All entry-point and edge FQNs follow this pattern:

```
<package>.<ClassName>#<methodName>(<ParamType1>, <ParamType2>)
```

Examples:

```
com.example.controller.UserController#getUser(Long)
com.example.service.UserServiceImpl#createUser(String, String)
com.example.mapper.UserMapper#selectById(Long)
```

Parameter types use **simple names** (e.g. `Long`, not `java.lang.Long`).
Nested classes use `$` as the separator (e.g. `Outer$Inner#method()`).

## EXIT STATUS

| Code | Meaning |
|------|---------|
| `0` | Success. |
| `1` | Usage error (unknown option, missing required argument, etc.). |
| `2` | Application error (database not found, entry method not in index, I/O failure, etc.). |

## FILES

| Path | Description |
|------|-------------|
| `.sourcelens.db` | Default SQLite call-graph database, written by `index` and read by `trace`. |

## EXAMPLES

**Index a project and generate a diagram in one pipeline:**

```bash
sourcelens index --source src/main/java --db myproject.db

sourcelens trace \
    --entry "com.example.controller.UserController#getUser(Long)" \
    --db myproject.db | \
  sourcelens render --output diagram.md
```

**Save the trace file for later rendering:**

```bash
sourcelens trace \
    --entry "com.example.controller.UserController#getUser(Long)" \
    --db myproject.db \
    --output trace.txt

sourcelens render --input trace.txt --output diagram.md
```

**Inverse trace — who calls `selectById`?**

```bash
sourcelens trace \
    --entry "com.example.mapper.UserMapper#selectById(Long)" \
    --db myproject.db \
    --callers \
    --output callers.txt

sourcelens render --input callers.txt
```

**Inverse trace split by root caller, rendered to a multi-section diagram:**

```bash
sourcelens trace \
    --entry "com.example.mapper.UserMapper#selectById(Long)" \
    --db myproject.db \
    --callers \
    --split | \
  sourcelens render --output split-diagram.md
```

**Limit traversal depth:**

```bash
sourcelens trace \
    --entry "com.example.controller.UserController#getUser(Long)" \
    --db myproject.db \
    --depth 5
```

## SEE ALSO

- `ROADMAP.md` — feature backlog, technical debt ledger, and design notes
- `docs/adr/` — Architectural Decision Records (JavaParser, SQLite, Mermaid, Picocli)
- `docs/features/` — per-feature design specifications
