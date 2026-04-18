package com.sourcelens.indexer;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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

        List<String[]> allClassNodes     = new ArrayList<>(); // [fqn, simpleName, packageName, isInterface]
        List<String[]> allHierarchyEdges = new ArrayList<>(); // [childFqn, parentFqn, relation]
        List<String[]> allNodes          = new ArrayList<>(); // [fqn, returnType, classFqn, methodName, params]
        List<String[]> allEdges          = new ArrayList<>(); // [callerFqn, calleeFqn, callerReturnType]
        int fileCount = 0;

        try (Stream<Path> walk = Files.walk(sourcePath)) {
            List<Path> javaFiles = walk
                .filter(p -> p.toString().endsWith(".java"))
                .toList();

            for (Path file : javaFiles) {
                try {
                    CompilationUnit cu = StaticJavaParser.parse(file);
                    List<String[]> edges = new ArrayList<>();
                    List<String[]> nodes = new ArrayList<>();
                    new ClassHierarchyVisitor(cu, allClassNodes, allHierarchyEdges).visit(cu, null);
                    new CallEdgeVisitor(cu, edges, nodes).visit(cu, null);
                    allEdges.addAll(edges);
                    allNodes.addAll(nodes);
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
            db.persistClassNodes(allClassNodes);
            db.persistClassHierarchy(allHierarchyEdges);
            db.persistNodes(allNodes);
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
    // Class hierarchy visitor
    // -------------------------------------------------------------------------

    /**
     * Visits every top-level class and interface declaration in a compilation unit,
     * recording the class metadata and its {@code implements}/{@code extends} relationships.
     *
     * <p>Nested classes (static inner classes, anonymous classes) are skipped: their
     * parent types are typically external-library types (e.g. {@code Comparator}) that
     * are not in the indexed source tree and would produce unresolvable hierarchy entries.
     */
    private static final class ClassHierarchyVisitor extends VoidVisitorAdapter<Void> {

        private final CompilationUnit cu;
        private final List<String[]> classNodes;      // [fqn, simpleName, packageName, isInterface]
        private final List<String[]> hierarchyEdges;  // [childFqn, parentFqn, relation]

        ClassHierarchyVisitor(CompilationUnit cu, List<String[]> classNodes, List<String[]> hierarchyEdges) {
            this.cu = cu;
            this.classNodes = classNodes;
            this.hierarchyEdges = hierarchyEdges;
        }

        @Override
        public void visit(ClassOrInterfaceDeclaration n, Void arg) {
            // Skip nested classes — their parent is a TypeDeclaration, not a CompilationUnit
            boolean isNested = n.getParentNode()
                .map(p -> p instanceof TypeDeclaration)
                .orElse(false);
            if (isNested) {
                super.visit(n, arg);
                return;
            }

            String pkg = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("");
            String simpleName = n.getNameAsString();
            String classFqn   = pkg.isEmpty() ? simpleName : pkg + "." + simpleName;
            boolean isIface   = n.isInterface();

            classNodes.add(new String[]{classFqn, simpleName, pkg, String.valueOf(isIface)});

            addHierarchyEdges(classFqn, n.getImplementedTypes(), "IMPLEMENTS");
            addHierarchyEdges(classFqn, n.getExtendedTypes(),    "EXTENDS");

            super.visit(n, arg);
        }

        private void addHierarchyEdges(String childFqn,
                                        Iterable<ClassOrInterfaceType> types,
                                        String relation) {
            for (ClassOrInterfaceType type : types) {
                try {
                    String parentFqn = type.resolve().asReferenceType().getQualifiedName();
                    hierarchyEdges.add(new String[]{childFqn, parentFqn, relation});
                } catch (UnsolvedSymbolException | UnsupportedOperationException e) {
                    log.debug("Skipping unresolved {} type '{}' in {}",
                            relation, type.getNameAsString(), childFqn);
                } catch (Exception e) {
                    log.debug("Skipping {} type '{}' in {}: {}",
                            relation, type.getNameAsString(), childFqn, e.getMessage());
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Call edge visitor
    // -------------------------------------------------------------------------

    /**
     * Visits every method declaration and every method call expression in a
     * compilation unit, collecting directed edges {callerFqn, calleeFqn, callerReturnType}.
     *
     * The visitor context ({@code arg}) is a two-element {@code String[]} carrying
     * {@code [callerFqn, callerReturnType]}.  This avoids a separate bookkeeping map
     * while keeping the visitor stateless between declarations.
     */
    private static final class CallEdgeVisitor extends VoidVisitorAdapter<String[]> {

        private final CompilationUnit cu;
        private final List<String[]> edges;
        private final List<String[]> nodes;

        CallEdgeVisitor(CompilationUnit cu, List<String[]> edges, List<String[]> nodes) {
            this.cu = cu;
            this.edges = edges;
            this.nodes = nodes;
        }

        @Override
        public void visit(MethodDeclaration n, String[] ignored) {
            String callerFqn  = buildCallerFqn(n);
            String returnType = n.getType().asString();
            int hash = callerFqn.indexOf('#');
            String classFqn   = hash >= 0 ? callerFqn.substring(0, hash) : callerFqn;
            String methodName = n.getNameAsString();
            String params     = n.getParameters().stream()
                .map(p -> p.getType().asString())
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
            nodes.add(new String[]{callerFqn, returnType, classFqn, methodName, params});
            super.visit(n, new String[]{callerFqn, returnType});
        }

        @Override
        public void visit(MethodCallExpr n, String[] ctx) {
            if (ctx == null) {
                super.visit(n, null);
                return;
            }
            String callerFqn        = ctx[0];
            String callerReturnType = ctx[1];
            String calleeFqn        = resolveCallee(n);
            edges.add(new String[]{callerFqn, calleeFqn, callerReturnType});
            super.visit(n, ctx);
        }

        // ------------------------------------------------------------------

        private String buildCallerFqn(MethodDeclaration n) {
            String pkg = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString() + ".")
                .orElse("");

            String className = buildEnclosingClassName(n);

            String paramTypes = n.getParameters().stream()
                .map(p -> p.getType().asString())
                .reduce((a, b) -> a + ", " + b)
                .orElse("");

            return pkg + className + "#" + n.getNameAsString() + "(" + paramTypes + ")";
        }

        /**
         * Walks all ancestor nodes to build a fully-qualified class name that includes
         * outer class names and anonymous class markers.
         *
         * Examples:
         *   Outer.Inner method  →  "Outer$Inner"
         *   anonymous class     →  "Outer$anonymous:42"  (42 = source line)
         *   enum inner class    →  "MyEnum$Inner"
         */
        private static String buildEnclosingClassName(Node startNode) {
            Deque<String> parts = new ArrayDeque<>();
            Node current = startNode.getParentNode().orElse(null);
            while (current != null) {
                if (current instanceof ClassOrInterfaceDeclaration cid) {
                    parts.addFirst(cid.getNameAsString());
                } else if (current instanceof EnumDeclaration ed) {
                    parts.addFirst(ed.getNameAsString());
                } else if (current instanceof ObjectCreationExpr oce
                        && oce.getAnonymousClassBody().isPresent()) {
                    String line = oce.getBegin()
                        .map(p -> String.valueOf(p.line))
                        .orElse("?");
                    parts.addFirst("anonymous:" + line);
                }
                current = current.getParentNode().orElse(null);
            }
            return parts.isEmpty() ? "<unknown>" : String.join("$", parts);
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
