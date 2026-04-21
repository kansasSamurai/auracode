package com.sourcelens.integration;

import com.sourcelens.Main;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
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

        // Feature 2.3r1: hierarchy-aware dispatch finds BOTH UserService implementations.
        // CachedUserServiceImpl has no call to UserMapper — it stops at findById.
        assertTrue(diagram.contains("participant CachedUserServiceImpl"),
                "Diagram should declare CachedUserServiceImpl participant");
        assertTrue(diagram.contains("UserController->>CachedUserServiceImpl: findById(Long)"),
                "Diagram should contain UserController → CachedUserServiceImpl edge");
        assertTrue(diagram.contains("CachedUserServiceImpl-->>UserController: User"),
                "Diagram should contain return arrow CachedUserServiceImpl → UserController");

        // ProductServiceImpl must NOT appear — it implements an unrelated interface
        assertFalse(diagram.contains("ProductService"),
                "Diagram must not contain ProductService (unrelated hierarchy)");
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

        // Upstream caller via UserService interface (hierarchy-aware, not heuristic)
        assertTrue(trace.contains(
                "com.example.controller.UserController#getUser(Long) -> com.example.service.UserServiceImpl#findById(Long)"),
                "Trace should contain upstream edge: UserController -> UserServiceImpl");

        // CachedUserServiceImpl must NOT appear — it never calls UserMapper#selectById
        assertFalse(trace.contains("CachedUserServiceImpl"),
                "Inverse trace from UserMapper must not include CachedUserServiceImpl");
    }

    @Test
    void hierarchyDisambiguation_concreteCalleesForUserService_excludesProductService(
            @TempDir Path tempDir) throws IOException {

        Path dbFile = tempDir.resolve("test.db");

        int indexExit = cli("index",
                "--source", FIXTURE_SOURCE.toString(),
                "--db",     dbFile.toString());
        assertEquals(0, indexExit, "index command should exit 0");

        // Forward trace from UserController must include BOTH UserService implementations
        // but must NOT include ProductServiceImpl (different hierarchy).
        Path traceFile  = tempDir.resolve("trace.txt");
        Path diagramFile = tempDir.resolve("diagram.md");

        cli("trace",
                "--entry",  ENTRY_METHOD,
                "--db",     dbFile.toString(),
                "--output", traceFile.toString());

        cli("render",
                "--input",  traceFile.toString(),
                "--output", diagramFile.toString());

        String diagram = Files.readString(diagramFile);

        // Both UserService implementations must appear
        assertTrue(diagram.contains("participant UserServiceImpl"),
                "Diagram must include UserServiceImpl (implements UserService)");
        assertTrue(diagram.contains("participant CachedUserServiceImpl"),
                "Diagram must include CachedUserServiceImpl (also implements UserService)");

        // ProductServiceImpl implements a different interface — must not bleed in
        assertFalse(diagram.contains("ProductService"),
                "Diagram must not include ProductService (unrelated hierarchy)");
    }

    @Test
    void defaultIndex_filterExternalCalls_noExternalMethodNodes(@TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("test.db");

        int exit = cli("index", "--source", FIXTURE_SOURCE.toString(), "--db", dbFile.toString());
        assertEquals(0, exit, "index command should exit 0");

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
             Statement st = conn.createStatement()) {

            // No java.* FQNs — Java SDK calls must be filtered
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM method_node WHERE fqn LIKE 'java.%'")) {
                assertEquals(0, rs.getLong(1),
                        "Default index must not persist Java SDK method nodes");
            }

            // No unresolved (?) calls — DEBT-002 fallback entries must be filtered
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM method_node WHERE fqn LIKE '%(?)'")) {
                assertEquals(0, rs.getLong(1),
                        "Default index must not persist unresolved external method nodes");
            }
        }
    }

    @Test
    void includeExternalFlag_presentsExternalMethodNodes(@TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("test.db");

        int exit = cli("index",
                "--source", FIXTURE_SOURCE.toString(),
                "--db",     dbFile.toString(),
                "--include-external");
        assertEquals(0, exit, "index --include-external should exit 0");

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
             Statement st = conn.createStatement()) {

            // At least one external node must exist (the fixture uses HashMap, String, etc.)
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM method_node WHERE fqn LIKE 'java.%' OR fqn LIKE '%(?)'")) {
                assertTrue(rs.getLong(1) > 0,
                        "--include-external must persist at least one Java SDK or unresolved node");
            }
        }
    }

    @Test
    void stackBasedRender_createUser_returnsInterleavedWithForwardCalls(
            @TempDir Path tempDir) throws IOException {

        Path dbFile      = tempDir.resolve("test.db");
        Path traceFile   = tempDir.resolve("trace.txt");
        Path diagramFile = tempDir.resolve("diagram.md");

        cli("index", "--source", FIXTURE_SOURCE.toString(), "--db", dbFile.toString());
        cli("trace",
                "--entry",  "com.example.service.UserServiceImpl#createUser(String, String)",
                "--db",     dbFile.toString(),
                "--output", traceFile.toString());
        cli("render", "--input", traceFile.toString(), "--output", diagramFile.toString());

        String diagram = Files.readString(diagramFile);

        int insertForwardIdx = diagram.indexOf("UserServiceImpl->>UserMapper: insert(User)");
        int insertReturnIdx  = diagram.indexOf("UserMapper-->>UserServiceImpl: int");
        int selectForwardIdx = diagram.indexOf("UserServiceImpl->>UserMapper: selectById(Long)");

        assertTrue(insertForwardIdx >= 0, "insert forward call must appear in diagram");
        assertTrue(insertReturnIdx  >= 0, "insert int return must appear in diagram");
        assertTrue(selectForwardIdx >= 0, "selectById forward call must appear in diagram");

        assertTrue(insertForwardIdx < insertReturnIdx,
                "insert forward must appear before int return arrow");
        assertTrue(insertReturnIdx < selectForwardIdx,
                "int return arrow must appear before selectById forward call (stack-based interleaving)");
    }

    @Test
    void argCallTagging_createUser_getIdTaggedAsArgCall(@TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("test.db");

        int exit = cli("index", "--source", FIXTURE_SOURCE.toString(), "--db", dbFile.toString());
        assertEquals(0, exit, "index command should exit 0");

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT n2.fqn, e.is_arg_call
                     FROM   method_node n1
                     JOIN   call_edge   e  ON e.caller_id = n1.id
                     JOIN   method_node n2 ON n2.id       = e.callee_id
                     WHERE  n1.fqn = ?
                     ORDER BY e.call_sequence
                     """)) {
            ps.setString(1, "com.example.service.UserServiceImpl#createUser(String, String)");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String calleeName = rs.getString(1);
                    int isArgCall     = rs.getInt(2);
                    if (calleeName.contains("getId")) {
                        assertEquals(1, isArgCall,
                                "User#getId() is an argument to selectById — must be tagged is_arg_call=1");
                    } else {
                        assertEquals(0, isArgCall,
                                calleeName + " is a top-level call — must have is_arg_call=0");
                    }
                }
            }
        }
    }

    @Test
    void argCallSuppression_defaultTrace_excludesArgCalls(@TempDir Path tempDir) throws Exception {
        Path dbFile    = tempDir.resolve("test.db");
        Path traceFile = tempDir.resolve("trace.txt");

        cli("index", "--source", FIXTURE_SOURCE.toString(), "--db", dbFile.toString());
        int traceExit = cli("trace",
                "--entry",  "com.example.service.UserServiceImpl#createUser(String, String)",
                "--db",     dbFile.toString(),
                "--output", traceFile.toString());

        assertEquals(0, traceExit, "trace command should exit 0");
        String trace = Files.readString(traceFile);

        assertTrue(trace.contains("selectById"),
                "Default trace must include selectById (top-level call)");
        assertFalse(trace.contains("getId"),
                "Default trace must NOT include getId (argument-position call)");
    }

    @Test
    void argCallSuppression_includeArgCallsFlag_surfacesArgCalls(@TempDir Path tempDir) throws Exception {
        Path dbFile    = tempDir.resolve("test.db");
        Path traceFile = tempDir.resolve("trace.txt");

        cli("index", "--source", FIXTURE_SOURCE.toString(), "--db", dbFile.toString());
        int traceExit = cli("trace",
                "--entry",           "com.example.service.UserServiceImpl#createUser(String, String)",
                "--db",              dbFile.toString(),
                "--output",          traceFile.toString(),
                "--include-arg-calls");

        assertEquals(0, traceExit, "trace --include-arg-calls should exit 0");
        String trace = Files.readString(traceFile);

        assertTrue(trace.contains("selectById"),
                "--include-arg-calls trace must include selectById");
        assertTrue(trace.contains("getId"),
                "--include-arg-calls trace must include getId (argument-position call)");
    }

    @Test
    void callOrderPreserved_createUser_calleesInSourceOrder(@TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("test.db");

        int exit = cli("index", "--source", FIXTURE_SOURCE.toString(), "--db", dbFile.toString());
        assertEquals(0, exit, "index command should exit 0");

        // Query callee FQNs ordered by call_sequence for UserServiceImpl#createUser
        List<String> calleeFqns = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT n2.fqn
                     FROM   method_node n1
                     JOIN   call_edge   e  ON e.caller_id = n1.id
                     JOIN   method_node n2 ON n2.id       = e.callee_id
                     WHERE  n1.fqn = ?
                     ORDER BY e.call_sequence
                     """)) {
            ps.setString(1, "com.example.service.UserServiceImpl#createUser(String, String)");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) calleeFqns.add(rs.getString(1));
            }
        }

        // Source order: setUsername(1) → setEmail(2) → insert(3) → selectById(4)
        assertTrue(calleeFqns.size() >= 4, "createUser must have at least 4 callee edges");

        List<String> simpleNames = calleeFqns.stream()
                .map(fqn -> fqn.contains("#") ? fqn.substring(fqn.indexOf('#') + 1) : fqn)
                .toList();

        int setUsernameIdx = simpleNames.indexOf("setUsername(String)");
        int setEmailIdx    = simpleNames.indexOf("setEmail(String)");
        int insertIdx      = simpleNames.indexOf("insert(User)");
        int selectByIdIdx  = simpleNames.indexOf("selectById(Long)");

        assertTrue(setUsernameIdx >= 0, "setUsername must be a callee of createUser");
        assertTrue(setEmailIdx    >= 0, "setEmail must be a callee of createUser");
        assertTrue(insertIdx      >= 0, "insert must be a callee of createUser");
        assertTrue(selectByIdIdx  >= 0, "selectById must be a callee of createUser");

        assertTrue(setUsernameIdx < setEmailIdx,
                "setUsername (seq " + setUsernameIdx + ") must precede setEmail (seq " + setEmailIdx + ")");
        assertTrue(setEmailIdx < insertIdx,
                "setEmail (seq " + setEmailIdx + ") must precede insert (seq " + insertIdx + ")");
        assertTrue(insertIdx < selectByIdIdx,
                "insert (seq " + insertIdx + ") must precede selectById (seq " + selectByIdIdx + ")");
    }

    // -------------------------------------------------------------------------

    private static int cli(String... args) {
        return new CommandLine(new Main()).execute(args);
    }
}
