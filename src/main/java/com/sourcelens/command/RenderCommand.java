package com.sourcelens.command;

import com.sourcelens.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Command(
    name = "render",
    mixinStandardHelpOptions = true,
    description = "Convert a trace edge list to a Mermaid sequenceDiagram fenced code block."
)
public class RenderCommand implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(RenderCommand.class);

    @Option(names = {"-i", "--input"},
            description = "Edge list file from 'trace' (default: stdin).")
    private Path inputPath;

    @Option(names = {"-o", "--output"},
            description = "Write Mermaid diagram to file (default: stdout).")
    private Path outputPath;

    @Override
    public void run() {
        if (inputPath != null) {
            Assert.fileExists(inputPath, "Input file not found: " + inputPath.toAbsolutePath());
        }

        try (BufferedReader reader = openReader();
             PrintWriter writer = openWriter()) {

            // TODO: [DEBT-009] extract rendering logic into RenderService interface + DefaultRenderService in hardening
            List<String> allLines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                allLines.add(line);
            }

            List<Section> sections = parseSections(allLines);
            for (int i = 0; i < sections.size(); i++) {
                Section section = sections.get(i);
                if (section.label != null) {
                    writer.println("## " + toParticipant(section.label) + ": " + toMessage(section.label));
                    writer.println();
                }
                if (section.edges.isEmpty()) {
                    log.warn("Section '{}' has no edges — emitting empty diagram.",
                            section.label != null ? section.label : "(default)");
                }
                writeDiagram(section.edges, section.participants, writer);
                if (sections.size() > 1 && i < sections.size() - 1) {
                    writer.println(); // blank line between blocks
                }
            }
            log.info("Rendered {} section(s).", sections.size());

        } catch (IOException e) {
            throw new RuntimeException("I/O error during render: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Domain types
    // -------------------------------------------------------------------------

    /**
     * A single directed call edge from the trace file.
     *
     * @param callerFqn  fully-qualified caller method
     * @param calleeFqn  fully-qualified callee method
     * @param returnType return type of the callee, or {@code null} when unknown
     */
    private record Edge(String callerFqn, String calleeFqn, String returnType) {}

    // -------------------------------------------------------------------------
    // Diagram writer
    // -------------------------------------------------------------------------

    /**
     * Emits a fenced Mermaid {@code sequenceDiagram} block.
     *
     * Forward call arrows ({@code ->>}) are emitted in edge order.
     * Dashed return arrows ({@code -->>}) are then emitted in reverse order so
     * that each callee "returns" to its caller after the full call stack has been
     * shown.  {@code void} returns and edges with no resolved return type are
     * silently suppressed.
     */
    private static void writeDiagram(List<Edge> edges, Set<String> participants, PrintWriter w) {
        w.println("```mermaid");
        w.println("sequenceDiagram");
        for (String p : participants) {
            w.println("    participant " + p);
        }
        // Forward call arrows
        for (Edge edge : edges) {
            String from = toParticipant(edge.callerFqn());
            String to   = toParticipant(edge.calleeFqn());
            String msg  = toMessage(edge.calleeFqn());
            w.println("    " + from + "->>" + to + ": " + msg);
        }
        // Return arrows — reversed, void / unknown suppressed
        List<Edge> reversed = new ArrayList<>(edges);
        Collections.reverse(reversed);
        for (Edge edge : reversed) {
            String rt = edge.returnType();
            if (rt == null || rt.isEmpty() || rt.equals("void")) continue;
            String from = toParticipant(edge.callerFqn());
            String to   = toParticipant(edge.calleeFqn());
            w.println("    " + to + "-->>" + from + ": " + rt);
        }
        w.println("```");
    }

    // -------------------------------------------------------------------------
    // Multi-section support
    // -------------------------------------------------------------------------

    private static class Section {
        final String label; // root FQN from "=== <fqn> ===" header, or null for single-section input
        final List<Edge> edges = new ArrayList<>();
        final Set<String> participants = new LinkedHashSet<>();

        Section(String label) {
            this.label = label;
        }
    }

    /**
     * Splits input lines into sections on {@code === <fqn> ===} headers.
     * If no headers are found, returns a single section with label=null (backward compatible).
     *
     * <p>Edge lines may carry an optional return-type suffix added by Feature 2.6:
     * {@code callerFqn -> calleeFqn : ReturnType}.  Lines without the suffix are
     * parsed with {@code returnType = null}.
     */
    private static List<Section> parseSections(List<String> lines) {
        List<Section> sections = new ArrayList<>();
        Section current = null;

        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("===") && line.endsWith("===") && line.length() > 6) {
                String label = line.substring(3, line.length() - 3).trim();
                current = new Section(label);
                sections.add(current);
                continue;
            }

            if (current == null) {
                // No header seen yet — treat all lines as a single unlabelled section
                current = new Section(null);
                sections.add(current);
            }

            int sep = line.indexOf(" -> ");
            if (sep < 0) {
                continue; // skip malformed lines
            }
            String callerFqn = line.substring(0, sep).trim();
            String rest      = line.substring(sep + 4).trim();

            // Parse optional " : ReturnType" suffix (Feature 2.6).
            // FQNs never contain " : ", so the last occurrence is the separator.
            String calleeFqn;
            String returnType = null;
            int rtSep = rest.lastIndexOf(" : ");
            if (rtSep >= 0) {
                calleeFqn  = rest.substring(0, rtSep).trim();
                returnType = rest.substring(rtSep + 3).trim();
            } else {
                calleeFqn = rest;
            }

            current.participants.add(toParticipant(callerFqn));
            current.participants.add(toParticipant(calleeFqn));
            current.edges.add(new Edge(callerFqn, calleeFqn, returnType));
        }

        if (sections.isEmpty()) {
            sections.add(new Section(null)); // empty input — emit empty diagram
        }
        return sections;
    }

    // -------------------------------------------------------------------------
    // FQN helpers
    // -------------------------------------------------------------------------

    /**
     * Extracts a Mermaid-safe participant name from a method FQN.
     *
     * "com.example.Foo$Bar#method(Param)"  →  "Foo_Bar"
     *
     * // TODO: [DEBT-008] uses simple class name — two classes with the same simple name
     * //   in different packages will collide; use aliased FQN in hardening pass
     */
    static String toParticipant(String fqn) {
        int hash = fqn.indexOf('#');
        String classFqn = hash >= 0 ? fqn.substring(0, hash) : fqn;
        int lastDot = classFqn.lastIndexOf('.');
        String simpleName = lastDot >= 0 ? classFqn.substring(lastDot + 1) : classFqn;
        return sanitize(simpleName);
    }

    /**
     * Extracts the method signature (name + params) from a callee FQN for use as
     * the Mermaid message label.
     *
     * "com.example.Foo#method(Param)"  →  "method(Param)"
     */
    static String toMessage(String fqn) {
        int hash = fqn.indexOf('#');
        return hash >= 0 ? fqn.substring(hash + 1) : fqn;
    }

    /**
     * Replaces characters that are unsafe in Mermaid participant identifiers.
     * ADR-003: replace {@code $} and {@code :} with {@code _}.
     */
    static String sanitize(String s) {
        return s.replace('$', '_').replace(':', '_');
    }

    // -------------------------------------------------------------------------
    // I/O helpers
    // -------------------------------------------------------------------------

    private BufferedReader openReader() throws IOException {
        if (inputPath != null) {
            log.info("Reading edge list from {}", inputPath.toAbsolutePath());
            return Files.newBufferedReader(inputPath);
        }
        log.debug("Reading edge list from stdin.");
        return new BufferedReader(new InputStreamReader(System.in));
    }

    private PrintWriter openWriter() throws IOException {
        if (outputPath != null) {
            log.info("Writing diagram to {}", outputPath.toAbsolutePath());
            return new PrintWriter(Files.newBufferedWriter(outputPath));
        }
        return new PrintWriter(System.out, true);
    }
}
