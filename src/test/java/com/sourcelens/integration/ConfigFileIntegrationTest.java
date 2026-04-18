package com.sourcelens.integration;

import com.sourcelens.Main;
import com.sourcelens.config.DefaultConfigProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for Feature 2.5 — config file support.
 *
 * <p>Each test writes a {@code .sourcelens} file into a temp directory and
 * passes that directory as the {@code baseDir} to {@link DefaultConfigProvider},
 * so the production CWD is never read or polluted.
 */
class ConfigFileIntegrationTest {

    private static final Path FIXTURE_SOURCE =
            Path.of("test-fixtures/mybatis-sample/src");

    private static final String ENTRY_METHOD =
            "com.example.controller.UserController#getUser(Long)";

    // -------------------------------------------------------------------------
    // Test 1: config-driven index (no --source or --db flags on CLI)
    // -------------------------------------------------------------------------

    @Test
    void configDrivenIndex_noFlagsOnCli_usesConfigValues(@TempDir Path tempDir) throws IOException {
        Path dbFile = tempDir.resolve("config-driven.db");

        // Write config with both required (index.source) and optional (index.db) keys.
        // Use forward slashes: .properties treats backslash as an escape character,
        // so Windows paths must have \ replaced with / (both work on Windows).
        Files.writeString(tempDir.resolve(".sourcelens"),
                "index.source=" + toConfigPath(FIXTURE_SOURCE.toAbsolutePath()) + "\n" +
                "index.db="     + toConfigPath(dbFile.toAbsolutePath())         + "\n");

        int exit = cli(tempDir, "index");  // no --source, no --db

        assertEquals(0, exit, "index should exit 0 when source and db are supplied via config");
        assertTrue(Files.exists(dbFile), "database should be created at the path from config");
    }

    // -------------------------------------------------------------------------
    // Test 2: CLI flag overrides config value
    // -------------------------------------------------------------------------

    @Test
    void cliFlagOverridesConfigValue_depthFlag(@TempDir Path tempDir) throws IOException {
        Path dbFile    = tempDir.resolve("override-test.db");
        Path traceFile = tempDir.resolve("override-trace.txt");

        // Step 1: index via normal CLI flags
        int indexExit = cli(tempDir, "index",
                "--source", FIXTURE_SOURCE.toString(),
                "--db",     dbFile.toString());
        assertEquals(0, indexExit, "index should succeed");

        // Write config that sets depth=1 (would produce only 1 edge)
        Files.writeString(tempDir.resolve(".sourcelens"),
                "trace.depth=1\n" +
                "db=" + toConfigPath(dbFile.toAbsolutePath()) + "\n");

        // Step 2: trace with explicit --depth 99 — should override config's depth=1
        int traceExit = cli(tempDir, "trace",
                "--entry",  ENTRY_METHOD,
                "--db",     dbFile.toString(),
                "--depth",  "99",
                "--output", traceFile.toString());
        assertEquals(0, traceExit, "trace should succeed");

        String trace = Files.readString(traceFile);
        // depth=1 would stop after UserController->UserServiceImpl, producing only 1 edge.
        // depth=99 should traverse further and produce at least 2 edges.
        long edgeCount = trace.lines().filter(l -> l.contains(" -> ")).count();
        assertTrue(edgeCount >= 2,
                "CLI --depth 99 should override config depth=1; expected >=2 edges, got " + edgeCount);
    }

    // -------------------------------------------------------------------------
    // Test 3: shared bare key (db) applies to both index and trace
    // -------------------------------------------------------------------------

    @Test
    void sharedBareKey_appliedToMultipleCommands(@TempDir Path tempDir) throws IOException {
        Path dbFile    = tempDir.resolve("shared.db");
        Path traceFile = tempDir.resolve("shared-trace.txt");

        // Config has only a bare 'db' key and an index.source key
        Files.writeString(tempDir.resolve(".sourcelens"),
                "db="           + toConfigPath(dbFile.toAbsolutePath())          + "\n" +
                "index.source=" + toConfigPath(FIXTURE_SOURCE.toAbsolutePath())  + "\n");

        // index uses the bare db key (no --source, no --db on CLI)
        int indexExit = cli(tempDir, "index");
        assertEquals(0, indexExit, "index should pick up db and source from config");
        assertTrue(Files.exists(dbFile), "shared db should be created by index");

        // trace uses the same bare db key (no --db on CLI)
        int traceExit = cli(tempDir, "trace",
                "--entry",  ENTRY_METHOD,
                "--output", traceFile.toString());
        assertEquals(0, traceExit, "trace should pick up db from shared config key");
        assertFalse(Files.readString(traceFile).isBlank(), "trace output should not be empty");
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    /**
     * Executes a sourcelens command with a custom config base directory injected.
     * The {@link DefaultConfigProvider} reads {@code .sourcelens} from {@code configBase}
     * rather than from {@code user.dir}, keeping tests hermetic.
     */
    private static int cli(Path configBase, String... args) {
        return new CommandLine(new Main())
                .setDefaultValueProvider(new DefaultConfigProvider(configBase))
                .execute(args);
    }

    /**
     * Converts a Path to a string safe for use as a .properties file value.
     * Java's .properties format treats backslash as an escape character, so
     * Windows paths must use forward slashes (which the JVM accepts on Windows).
     */
    private static String toConfigPath(Path path) {
        return path.toString().replace('\\', '/');
    }
}
