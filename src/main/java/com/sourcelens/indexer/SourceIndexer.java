package com.sourcelens.indexer;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.sourcelens.db.CallGraphDb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Walks a Java source tree, parses every .java file with JavaParser,
 * extracts method→method call edges, and persists them to a SQLite database.
 */
public class SourceIndexer {

    private static final Logger log = LoggerFactory.getLogger(SourceIndexer.class);

    private final Path dbPath;

    public SourceIndexer(Path dbPath) {
        this.dbPath = dbPath;
    }

    public void index(Path sourcePath) {
        configureParser(sourcePath);

        List<String[]> allEdges = new ArrayList<>();
        int fileCount = 0;

        try (Stream<Path> walk = Files.walk(sourcePath)) {
            List<Path> javaFiles = walk
                .filter(p -> p.toString().endsWith(".java"))
                .toList();

            for (Path file : javaFiles) {
                try {
                    CompilationUnit cu = StaticJavaParser.parse(file);
                    List<String[]> edges = new ArrayList<>();
                    new CallEdgeVisitor(cu, edges).visit(cu, null);
                    allEdges.addAll(edges);
                    fileCount++;
                } catch (Exception e) {
                    log.warn("Skipping {} — parse error: {}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to walk source path: " + sourcePath, e);
        }

        try (CallGraphDb db = new CallGraphDb(dbPath)) {
            db.init();
            db.persistEdges(allEdges);
            long nodes = db.countNodes();
            long edges = db.countEdges();
            log.info("Indexed {} files → {} nodes, {} edges persisted to {}",
                fileCount, nodes, edges, dbPath.toAbsolutePath());
        }
    }

    private static void configureParser(Path sourcePath) {
        CombinedTypeSolver solver = new CombinedTypeSolver(
            new ReflectionTypeSolver(),
            new JavaParserTypeSolver(sourcePath)
        );
        ParserConfiguration cfg = new ParserConfiguration()
            .setLanguageLevel(LanguageLevel.JAVA_8)
            .setSymbolResolver(new JavaSymbolSolver(solver));
        StaticJavaParser.setConfiguration(cfg);
    }

    // -------------------------------------------------------------------------
    // Visitor
    // -------------------------------------------------------------------------

    /**
     * Visits every method declaration and every method call expression in a
     * compilation unit, collecting directed edges {callerFqn, calleeFqn}.
     */
    private static final class CallEdgeVisitor extends VoidVisitorAdapter<String> {

        private final CompilationUnit cu;
        private final List<String[]> edges;

        CallEdgeVisitor(CompilationUnit cu, List<String[]> edges) {
            this.cu = cu;
            this.edges = edges;
        }

        @Override
        public void visit(MethodDeclaration n, String ignored) {
            String callerFqn = buildCallerFqn(n);
            // Recurse into the method body, passing callerFqn as the arg
            super.visit(n, callerFqn);
        }

        @Override
        public void visit(MethodCallExpr n, String callerFqn) {
            if (callerFqn == null) {
                super.visit(n, null);
                return;
            }
            String calleeFqn = resolveCallee(n);
            edges.add(new String[]{callerFqn, calleeFqn});
            super.visit(n, callerFqn);
        }

        // ------------------------------------------------------------------

        private String buildCallerFqn(MethodDeclaration n) {
            // TODO: [DEBT-001] nested/anonymous classes yield wrong FQN — top-level class only
            String pkg = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString() + ".")
                .orElse("");

            String className = n.findAncestor(ClassOrInterfaceDeclaration.class)
                .map(c -> c.getNameAsString())
                .orElse("<unknown>");

            String paramTypes = n.getParameters().stream()
                .map(p -> p.getType().asString())
                .reduce((a, b) -> a + ", " + b)
                .orElse("");

            return pkg + className + "#" + n.getNameAsString() + "(" + paramTypes + ")";
        }

        private String resolveCallee(MethodCallExpr n) {
            try {
                return normalizeSignature(n.resolve().getQualifiedSignature());
            } catch (UnsolvedSymbolException | UnsupportedOperationException e) {
                // TODO: [DEBT-002] external-lib callee FQNs unresolved without full classpath
                String scope = n.getScope()
                    .map(s -> s.toString())
                    .orElse("<unknown>");
                return scope + "#" + n.getNameAsString() + "(?)";
            } catch (Exception e) {
                // Catch-all for any other resolution failure
                String scope = n.getScope()
                    .map(s -> s.toString())
                    .orElse("<unknown>");
                return scope + "#" + n.getNameAsString() + "(?)";
            }
        }

        /**
         * Converts a JavaParser qualified signature to the buildCallerFqn format:
         *   "pkg.Class.method(pkg.Type1, pkg.Type2)"  →  "pkg.Class#method(Type1, Type2)"
         */
        private static String normalizeSignature(String sig) {
            int paren = sig.indexOf('(');
            if (paren < 0) return sig;

            String classAndMethod = sig.substring(0, paren);
            String paramStr = sig.substring(paren + 1, sig.length() - 1);

            int lastDot = classAndMethod.lastIndexOf('.');
            String normalized = lastDot < 0
                    ? classAndMethod
                    : classAndMethod.substring(0, lastDot) + '#' + classAndMethod.substring(lastDot + 1);

            if (paramStr.isEmpty()) return normalized + "()";

            String[] parts = paramStr.split(",\\s*");
            StringBuilder sb = new StringBuilder(normalized).append('(');
            for (int i = 0; i < parts.length; i++) {
                String p = parts[i].trim();
                int dot = p.lastIndexOf('.');
                sb.append(dot < 0 ? p : p.substring(dot + 1));
                if (i < parts.length - 1) sb.append(", ");
            }
            return sb.append(')').toString();
        }
    }
}
