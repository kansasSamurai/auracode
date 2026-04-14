package com.sourcelens.command;

import com.sourcelens.Assert;
import com.sourcelens.db.CallGraphDb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Command(
    name = "trace",
    mixinStandardHelpOptions = true,
    description = "Depth-first traverse the call graph from an entry-point method and emit an ordered edge list."
)
public class TraceCommand implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(TraceCommand.class);

    @Option(names = {"-e", "--entry"}, required = true,
            description = "Fully-qualified entry-point method FQN (e.g. com.example.Foo#bar()).")
    private String entryFqn;

    @Option(names = {"-d", "--db"}, defaultValue = ".sourcelens.db",
            description = "SQLite database written by 'index' (default: .sourcelens.db).")
    private Path dbPath;

    @Option(names = {"-o", "--output"},
            description = "Write edge list to this file instead of stdout.")
    private Path outputPath;

    @Option(names = {"-n", "--depth"}, defaultValue = "50",
            description = "Maximum DFS traversal depth (default: 50).")
    private int maxDepth;

    @Override
    public void run() {
        Assert.fileExists(dbPath, "Database not found — run 'index' first: " + dbPath.toAbsolutePath());

        try (CallGraphDb db = new CallGraphDb(dbPath);
             PrintWriter writer = openWriter()) {

            Assert.isTrue(db.nodeExists(entryFqn), "Entry method not found in index: " + entryFqn);

            log.info("Tracing from '{}' (maxDepth={}, db={})",
                    entryFqn, maxDepth, dbPath.toAbsolutePath());

            // TODO: [DEBT-005] extract DFS into a TraceService interface + DefaultTraceService in hardening
            Set<String> visited = new LinkedHashSet<>();
            traverse(entryFqn, db, maxDepth, visited, writer);

            log.info("Trace complete — {} unique methods visited, {} edges emitted",
                    visited.size(), visited.size() > 0 ? visited.size() - 1 : 0);

        } catch (IOException e) {
            throw new RuntimeException("Failed to open output file: " + outputPath, e);
        }
    }

    private void traverse(String fqn, CallGraphDb db, int depth, Set<String> visited, PrintWriter writer) {
        if (depth <= 0) {
            log.warn("Max traversal depth reached at '{}'", fqn);
            return;
        }
        if (visited.contains(fqn)) {
            return; // cycle detected — silently skip
        }
        visited.add(fqn);

        List<String> callees = db.getCalleeFqns(fqn);
        for (String callee : callees) {
            // If the stored callee is an interface/abstract with no outgoing edges,
            // look for a concrete implementation that does.
            // TODO: [DEBT-007] prototype heuristic — see CallGraphDb.findConcreteCalleeFqns
            List<String> concrete = db.findConcreteCalleeFqns(callee);
            List<String> targets = concrete.isEmpty() ? List.of(callee) : concrete;
            for (String target : targets) {
                writer.println(fqn + " -> " + target);
                traverse(target, db, depth - 1, visited, writer);
            }
        }
    }

    private PrintWriter openWriter() throws IOException {
        if (outputPath != null) {
            log.info("Writing trace output to {}", outputPath.toAbsolutePath());
            return new PrintWriter(outputPath.toFile());
        }
        // TODO: [DEBT-006] --entry requires exact FQN; add substring/fuzzy match in hardening
        return new PrintWriter(System.out, true);
    }
}
