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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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

    @Option(names = {"--callers"},
            description = "Inverse mode: trace all callers of --entry upward (default: false).")
    private boolean callers;

    @Option(names = {"--split"},
            description = "Split output by root caller: one section per independent call chain. Use with --callers.")
    private boolean split;

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
            if (callers && split) {
                traverseCallersSplit(entryFqn, db, maxDepth, writer);
            } else if (callers) {
                traverseCallers(entryFqn, db, maxDepth, visited, writer);
            } else {
                traverse(entryFqn, db, maxDepth, visited, writer);
            }

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

    private void traverseCallers(String fqn, CallGraphDb db, int depth, Set<String> visited, PrintWriter writer) {
        if (depth <= 0) {
            log.warn("Max traversal depth reached at '{}'", fqn);
            return;
        }
        if (visited.contains(fqn)) {
            return; // cycle detected — silently skip
        }
        visited.add(fqn);

        List<String> directCallers = db.getCallerFqns(fqn);

        // TODO: [DEBT-010] prototype heuristic — callers may have stored calls against
        //   an interface FQN; fall back to suffix-match if no direct callers found.
        //   See also DEBT-011 for the deferred config-file mapping approach.
        List<String> effectiveCallers = directCallers.isEmpty()
                ? db.findInterfaceCallerFqns(fqn)
                : directCallers;

        for (String caller : effectiveCallers) {
            writer.println(caller + " -> " + fqn);
            traverseCallers(caller, db, depth - 1, visited, writer);
        }
    }

    private void traverseCallersSplit(String targetFqn, CallGraphDb db, int maxDepth, PrintWriter writer) {
        // Pass 1: collect full inverse subgraph as Map<callee, List<caller>>
        Map<String, List<String>> inverseAdj = new LinkedHashMap<>();
        collectInverseGraph(targetFqn, db, maxDepth, new HashSet<>(), inverseAdj);

        // Pass 2: build forward adjacency (caller -> callees) within the subgraph
        Map<String, List<String>> forwardAdj = new LinkedHashMap<>();
        Set<String> allCallees = new HashSet<>();
        for (Map.Entry<String, List<String>> entry : inverseAdj.entrySet()) {
            String callee = entry.getKey();
            for (String caller : entry.getValue()) {
                forwardAdj.computeIfAbsent(caller, k -> new ArrayList<>()).add(callee);
                allCallees.add(callee);
            }
        }

        // Roots: appear as callers but not as callees within the subgraph
        List<String> roots = forwardAdj.keySet().stream()
                .filter(n -> !allCallees.contains(n))
                .collect(Collectors.toList());

        if (roots.isEmpty()) {
            log.warn("No root callers found for '{}' — emitting flat output.", targetFqn);
            traverseCallers(targetFqn, db, maxDepth, new LinkedHashSet<>(), writer);
            return;
        }

        log.info("Split trace: {} root caller(s) found for '{}'.", roots.size(), targetFqn);
        for (String root : roots) {
            writer.println("=== " + root + " ===");
            emitChain(root, forwardAdj, new LinkedHashSet<>(), writer);
        }
    }

    private void collectInverseGraph(String fqn, CallGraphDb db, int depth,
                                     Set<String> visited,
                                     Map<String, List<String>> inverseAdj) {
        if (depth <= 0 || visited.contains(fqn)) return;
        visited.add(fqn);

        List<String> direct = db.getCallerFqns(fqn);
        // TODO: [DEBT-010] interface-caller heuristic — fall back to suffix-match
        List<String> effective = direct.isEmpty() ? db.findInterfaceCallerFqns(fqn) : direct;

        // Filter self-references: findInterfaceCallerFqns can return fqn itself when
        // the node calls an interface method sharing the same #method(params) suffix,
        // and the caller of that interface method happens to be fqn itself.
        effective = effective.stream().filter(c -> !c.equals(fqn)).collect(Collectors.toList());

        inverseAdj.put(fqn, effective);
        for (String caller : effective) {
            collectInverseGraph(caller, db, depth - 1, visited, inverseAdj);
        }
    }

    private void emitChain(String node, Map<String, List<String>> forwardAdj,
                            Set<String> visited, PrintWriter writer) {
        if (visited.contains(node)) return;
        visited.add(node);
        for (String callee : forwardAdj.getOrDefault(node, List.of())) {
            writer.println(node + " -> " + callee);
            emitChain(callee, forwardAdj, visited, writer);
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
