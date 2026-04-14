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
    // Diagram writer
    // -------------------------------------------------------------------------

    private static void writeDiagram(List<String[]> edges, Set<String> participants, PrintWriter w) {
        w.println("```mermaid");
        w.println("sequenceDiagram");
        for (String p : participants) {
            w.println("    participant " + p);
        }
        for (String[] edge : edges) {
            String from = toParticipant(edge[0]);
            String to   = toParticipant(edge[1]);
            String msg  = toMessage(edge[1]);
            w.println("    " + from + "->>" + to + ": " + msg);
        }
        w.println("```");
    }

    // -------------------------------------------------------------------------
    // Multi-section support
    // -------------------------------------------------------------------------

    private static class Section {
        final String label; // root FQN from "=== <fqn> ===" header, or null for single-section input
        final List<String[]> edges = new ArrayList<>();
        final Set<String> participants = new LinkedHashSet<>();

        Section(String label) {
            this.label = label;
        }
    }

    /**
     * Splits input lines into sections on {@code === <fqn> ===} headers.
     * If no headers are found, returns a single section with label=null (backward compatible).
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
            String calleeFqn = line.substring(sep + 4).trim();
            current.participants.add(toParticipant(callerFqn));
            current.participants.add(toParticipant(calleeFqn));
            current.edges.add(new String[]{callerFqn, calleeFqn});
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
