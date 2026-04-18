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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

        // Return arrows (Feature 2.6): dashed arrows in reverse call order
        assertTrue(diagram.contains("UserMapper-->>UserServiceImpl: User"),
                "Diagram should contain return arrow UserMapper → UserServiceImpl");
        assertTrue(diagram.contains("UserServiceImpl-->>UserController: User"),
                "Diagram should contain return arrow UserServiceImpl → UserController");
    }

    @Test
    void splitInverseTrace_userMapperSelectById_producesThreeSections(
            @TempDir Path tempDir) throws IOException {

        Path dbFile      = tempDir.resolve("test.db");
        Path traceFile   = tempDir.resolve("split-trace.txt");
        Path diagramFile = tempDir.resolve("split-diagram.md");

        // ------------------------------------------------------------------
        // Step 1: index
        // ------------------------------------------------------------------
        int indexExit = cli("index",
                "--source", FIXTURE_SOURCE.toString(),
                "--db",     dbFile.toString());
        assertEquals(0, indexExit, "index command should exit 0");

        // ------------------------------------------------------------------
        // Step 2: split inverse trace
        // ------------------------------------------------------------------
        int traceExit = cli("trace",
                "--entry",   "com.example.mapper.UserMapper#selectById(Long)",
                "--db",      dbFile.toString(),
                "--callers",
                "--split",
                "--output",  traceFile.toString());

        assertEquals(0, traceExit, "split trace command should exit 0");
        assertTrue(Files.exists(traceFile), "Split trace output file should be created");

        String trace = Files.readString(traceFile);
        assertFalse(trace.isBlank(), "Split trace output should not be empty");

        // Expect exactly 3 section headers
        Matcher sectionMatcher = Pattern.compile("^=== .+ ===$", Pattern.MULTILINE).matcher(trace);
        int sectionCount = 0;
        while (sectionMatcher.find()) sectionCount++;
        assertEquals(3, sectionCount, "Should have exactly 3 sections (one per root controller method)");

        // Each controller entry point should appear as a section header
        assertTrue(trace.contains("=== com.example.controller.UserController#getUser(Long) ==="),
                "Should have section for getUser");
        assertTrue(trace.contains("=== com.example.controller.UserController#createUser(String, String) ==="),
                "Should have section for createUser");
        assertTrue(trace.contains("=== com.example.controller.UserController#updateEmail(Long, String) ==="),
                "Should have section for updateEmail");

        // Each section should contain the edge down to selectById
        assertTrue(trace.contains("UserServiceImpl#findById(Long) -> com.example.mapper.UserMapper#selectById(Long)"),
                "getUser section should include findById -> selectById edge");
        assertTrue(trace.contains("UserServiceImpl#createUser(String, String) -> com.example.mapper.UserMapper#selectById(Long)"),
                "createUser section should include createUser(impl) -> selectById edge");
        assertTrue(trace.contains("UserServiceImpl#updateEmail(Long, String) -> com.example.mapper.UserMapper#selectById(Long)"),
                "updateEmail section should include updateEmail(impl) -> selectById edge");

        // ------------------------------------------------------------------
        // Step 3: render — should produce 3 labelled Mermaid blocks
        // ------------------------------------------------------------------
        int renderExit = cli("render",
                "--input",  traceFile.toString(),
                "--output", diagramFile.toString());

        assertEquals(0, renderExit, "render command should exit 0");
        assertTrue(Files.exists(diagramFile), "Diagram file should be created");

        String diagram = Files.readString(diagramFile);

        // Three sequenceDiagram blocks
        Matcher seqMatcher = Pattern.compile("sequenceDiagram").matcher(diagram);
        int seqCount = 0;
        while (seqMatcher.find()) seqCount++;
        assertEquals(3, seqCount, "Diagram should contain 3 sequenceDiagram blocks");

        // Three section headings (each starts with "## UserController:")
        Matcher headingMatcher = Pattern.compile("^## UserController:", Pattern.MULTILINE).matcher(diagram);
        int headingCount = 0;
        while (headingMatcher.find()) headingCount++;
        assertEquals(3, headingCount, "Diagram should contain 3 UserController section headings");

        // Return arrows (Feature 2.6): each section should have dashed return arrows
        assertTrue(diagram.contains("UserMapper-->>UserServiceImpl: User"),
                "Diagram should contain return arrow UserMapper → UserServiceImpl");
        assertTrue(diagram.contains("UserServiceImpl-->>UserController: User"),
                "Diagram should contain return arrow UserServiceImpl → UserController");
    }

    @Test
    void inverseTrace_userMapperSelectById_producesUpstreamEdges(
            @TempDir Path tempDir) throws IOException {

        Path dbFile    = tempDir.resolve("test.db");
        Path traceFile = tempDir.resolve("inverse-trace.txt");

        // ------------------------------------------------------------------
        // Step 1: index (same fixture)
        // ------------------------------------------------------------------
        int indexExit = cli("index",
                "--source", FIXTURE_SOURCE.toString(),
                "--db",     dbFile.toString());

        assertEquals(0, indexExit, "index command should exit 0");

        // ------------------------------------------------------------------
        // Step 2: inverse trace from the deepest node
        // ------------------------------------------------------------------
        int traceExit = cli("trace",
                "--entry",   "com.example.mapper.UserMapper#selectById(Long)",
                "--db",      dbFile.toString(),
                "--callers",
                "--output",  traceFile.toString());

        assertEquals(0, traceExit, "inverse trace command should exit 0");
        assertTrue(Files.exists(traceFile), "Inverse trace output file should be created");

        String trace = Files.readString(traceFile);
        assertFalse(trace.isBlank(), "Inverse trace output should not be empty");

        // Direct caller: UserServiceImpl calls UserMapper
        assertTrue(trace.contains(
                "com.example.service.UserServiceImpl#findById(Long) -> com.example.mapper.UserMapper#selectById(Long)"),
                "Trace should contain direct caller edge: UserServiceImpl -> UserMapper");

        // Upstream via heuristic: UserController calls UserServiceImpl (stored against interface)
        assertTrue(trace.contains(
                "com.example.controller.UserController#getUser(Long) -> com.example.service.UserServiceImpl#findById(Long)"),
                "Trace should contain upstream edge: UserController -> UserServiceImpl");
    }

    // -------------------------------------------------------------------------

    private static int cli(String... args) {
        return new CommandLine(new Main()).execute(args);
    }
}
