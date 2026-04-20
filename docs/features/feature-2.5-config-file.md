# Feature 2.5 — Config File Support

## Overview

Running auracode against a specific project typically requires the same flags every time
(`--source src/main/java --db myproject.db`).  Feature 2.5 lets a project commit a
`.auracode` file at its root so those flags become implicit defaults.  CLI flags always
take precedence — the config file only fills in values that were not supplied on the command line.

---

## CLI

No new flags.  Config file loading is automatic.  The file is searched on every invocation;
if neither candidate file is found the tool behaves exactly as before.

---

## Design

### File search order

Both paths are resolved relative to the directory from which the command is run (i.e. `user.dir`):

| Priority | Path |
|----------|------|
| 1 (checked first) | `.auracode` |
| 2 (fallback) | `src/etc/auracode/config.properties` |

The first file found wins; the other is ignored.  Both files use standard Java `.properties`
format — no new dependencies are required.

`.auracode` is the recommended path for most projects: it lives at the root alongside
`pom.xml`, is immediately visible, and is trivial to version-control.

`src/etc/auracode/config.properties` is provided as an alternative for teams whose
conventions prohibit dot-files at the project root (e.g. Maven projects with strict
`enforcer` rules).  `src/etc` is the industry-standard Maven convention for project
configuration that is not part of the build artifact.

### Key resolution

For each CLI option that was not explicitly provided, the provider is consulted.  It tries
two keys in order:

1. `<commandName>.<optionName>` — command-scoped (e.g. `index.db`)
2. `<optionName>` alone — shared across all commands (e.g. `db`)

`<optionName>` is the option's longest flag with `--` stripped (e.g. `--depth` → `depth`).

**Examples:**

| Config key | Applies to |
|------------|-----------|
| `db` | `--db` in both `index` and `trace` |
| `index.db` | `--db` in `index` only (overrides bare `db` for this command) |
| `trace.depth` | `--depth` in `trace` only |
| `index.source` | `--source` in `index` only |

### Picocli integration

The provider implements Picocli's `IDefaultValueProvider` interface and is registered at
startup via `CommandLine.setDefaultValueProvider(...)` in `Main.java`.  Picocli calls the
provider for each option that was not supplied on the command line; a `null` return means
"use Picocli's built-in default".

**Required options:** if `IDefaultValueProvider` returns a non-null value for a
`required = true` option, Picocli considers the requirement satisfied.  This means setting
`index.source` in the config file makes `--source` optional on the command line — the
primary use case for per-project config.

### Architecture

Follows the project's interface + DefaultImpl convention:

| Type | Class | Location |
|------|-------|----------|
| Interface | `ConfigProvider` | `com.sourcelens.config` |
| Implementation | `DefaultConfigProvider` | `com.sourcelens.config` |

`DefaultConfigProvider` implements both `ConfigProvider` (domain contract) and
`CommandLine.IDefaultValueProvider` (Picocli bridge).  The two constructors are:

- `public DefaultConfigProvider()` — production; reads from `user.dir`
- `DefaultConfigProvider(Path baseDir)` — package-private; used by tests to inject a temp directory

---

## Config file reference

Place this file as `.auracode` in your project root and uncomment the keys you want to set.

```properties
# .auracode — project defaults for auracode CLI
# CLI flags always override these values.
# Scoped key (index.db) takes precedence over bare key (db) for that command.

# -----------------------------------------------------------------------
# Shared defaults (apply to all subcommands that recognise --db)
# -----------------------------------------------------------------------
# db=.auracode.db

# -----------------------------------------------------------------------
# index subcommand
# -----------------------------------------------------------------------
# index.source=src/main/java     # set this to make --source optional on the CLI
# index.db=.auracode.db        # overrides shared 'db' for index only

# -----------------------------------------------------------------------
# trace subcommand
# -----------------------------------------------------------------------
# trace.db=.auracode.db        # overrides shared 'db' for trace only
# trace.depth=50
# trace.callers=false
# trace.split=false
# trace.output=trace.txt

# -----------------------------------------------------------------------
# render subcommand
# -----------------------------------------------------------------------
# render.input=trace.txt
# render.output=diagram.md
```

---

## Verification

```bash
# Create a config file in the project root
cat > .auracode <<'EOF'
index.source=test-fixtures/mybatis-sample/src
db=target/demo.db
EOF

# Run index with no flags — source and db come from config
java -jar target/auracode.jar index
# Expected: exit 0, target/demo.db created

# Run trace — db comes from config, entry still required on CLI
java -jar target/auracode.jar trace \
    --entry "com.example.controller.UserController#getUser(Long)"
# Expected: trace printed to stdout

# CLI flag overrides config value
java -jar target/auracode.jar trace \
    --entry "com.example.controller.UserController#getUser(Long)" \
    --db override.db
# Expected: uses override.db, not target/demo.db

# Run trace and render
auracode trace --callers --split --entry "com.example.mapper.UserMapper#selectById(Long)" | auracode render

# Clean up
rm .auracode
```

Run tests:
```bash
./mvnw test
# Expected: all tests pass (existing 9 + 8 unit + 3 integration = 20 total)
```

---

## Known limitations

- **Single source directory only.** `index.source` accepts one path.  Multi-source support
  (e.g. `src/main/java` + `src/generated/java`) is not in scope for this feature.
- **No environment variable interpolation.** Values like `${HOME}/myproject.db` are treated
  as literal strings.  Path interpolation can be added as a hardening item.
- **`.properties` format only.** YAML or TOML are not supported.  Note: `.properties` cannot
  express section-based structure, so when DEBT-011 (explicit interface→impl mappings) is
  addressed it will require either a separate file or a format upgrade.  See DEBT-011.
- **CWD-relative only.** The tool checks the directory from which it is invoked, not the
  location of the JAR.  A `~/.auracode` user-home fallback is deferred
  (see `// TODO: [DEBT-012]` in `DefaultConfigProvider`).
