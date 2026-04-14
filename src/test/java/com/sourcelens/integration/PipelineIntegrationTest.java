package com.sourcelens.integration;

import com.sourcelens.Main;
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
 * End-to-end pipeline test: index → trace → render.
 *
 * Runs all three commands programmatically against the mybatis-sample fixture
 * and asserts the final Mermaid diagram contains the expected participants and
 * call edges.
 */
class PipelineIntegrationTest {

    /** Relative to Maven project root — where Surefire forks the JVM. */
    private static final Path FIXTURE_SOURCE =
            Path.of("test-fixtures/mybatis-sample/src");

    private static final String ENTRY_METHOD =
            "com.example.controller.UserController#getUser(Long)";

    @Test
    void fullPipeline_userControllerGetUser_producesCorrectMermaidDiagram(
            @TempDir Path tempDir) throws IOException {

        Path dbFile      = tempDir.resolve("test.db");
        Path traceFile   = tempDir.resolve("trace.txt");
        Path diagramFile = tempDir.resolve("diagram.md");

        // ------------------------------------------------------------------
        // Step 1: index
        // ------------------------------------------------------------------
        int indexExit = cli("index",
                "--source", FIXTURE_SOURCE.toString(),
                "--db",     dbFile.toString());

        assertEquals(0, indexExit, "index command should exit 0");
        assertTrue(Files.exists(dbFile), "SQLite database should be created");

        // ------------------------------------------------------------------
        // Step 2: trace
        // ------------------------------------------------------------------
        int traceExit = cli("trace",
                "--entry",  ENTRY_METHOD,
                "--db",     dbFile.toString(),
                "--output", traceFile.toString());

        assertEquals(0, traceExit, "trace command should exit 0");
        assertTrue(Files.exists(traceFile), "Trace output file should be created");
        assertFalse(Files.readString(traceFile).isBlank(), "Trace output should not be empty");

        // ------------------------------------------------------------------
        // Step 3: render
        // ------------------------------------------------------------------
        int renderExit = cli("render",
                "--input",  traceFile.toString(),
                "--output", diagramFile.toString());

        assertEquals(0, renderExit, "render command should exit 0");
        assertTrue(Files.exists(diagramFile), "Diagram file should be created");

        // ------------------------------------------------------------------
        // Assert diagram content
        // ------------------------------------------------------------------
        String diagram = Files.readString(diagramFile);

        assertTrue(diagram.contains("```mermaid"),
                "Diagram should be fenced as mermaid");
        assertTrue(diagram.contains("sequenceDiagram"),
                "Diagram should contain sequenceDiagram keyword");

        assertTrue(diagram.contains("participant UserController"),
                "Diagram should declare UserController participant");
        assertTrue(diagram.contains("participant UserServiceImpl"),
                "Diagram should declare UserServiceImpl participant");
        assertTrue(diagram.contains("participant UserMapper"),
                "Diagram should declare UserMapper participant");

        assertTrue(diagram.contains("UserController->>UserServiceImpl: findById(Long)"),
                "Diagram should contain UserController → UserServiceImpl edge");
        assertTrue(diagram.contains("UserServiceImpl->>UserMapper: selectById(Long)"),
                "Diagram should contain UserServiceImpl → UserMapper edge");
    }

    // -------------------------------------------------------------------------

    private static int cli(String... args) {
        return new CommandLine(new Main()).execute(args);
    }
}
