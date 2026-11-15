package com.github.yarnpatcher;

import java.util.stream.Collectors;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import javax.tools.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Reuses short parameter aliases across independent methods. */
final class ParameterNames {
    private static final class Token {
        private final String name;
        private final int offset;
        Token(String name, int offset) {
            this.name = name;
            this.offset = offset;
        }
        String name() {
            return name;
        }
        int offset() {
            return offset;
        }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Token)) return false;
            Token that = (Token) other;
            return java.util.Objects.equals(name, that.name) && java.util.Objects.equals(offset, that.offset);
        }
        @Override public int hashCode() {
            return java.util.Objects.hash(name, offset);
        }
    }
    static Map<Path, Map<String, String>> plan(List<Path> files, Map<String, String> mappings) throws IOException {
        Map<Path, Map<String, String>> result = new HashMap<>();
        javax.tools.JavaCompiler compiler = Jdk8Compat.compiler();
        javax.tools.DiagnosticCollector<javax.tools.JavaFileObject> diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (javax.tools.StandardJavaFileManager manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) compiler.getTask(null, manager, diagnostics, Jdk8Compat.list("-proc:none", "-encoding", "UTF-8"), null, Jdk8Compat.sourceFiles(manager, files));
            com.sun.source.util.SourcePositions positions = Trees.instance(task).getSourcePositions();
            for (com.sun.source.tree.CompilationUnitTree unit : task.parse()) {
                Path path = java.nio.file.Paths.get(unit.getSourceFile().toUri()).toAbsolutePath().normalize();
                Set<String> existing = new HashSet<>();
                List<Token> parameters = new ArrayList<>();
                List<Token> identifiers = new ArrayList<>();
                SrgMemberRewriter.replace(Jdk8Compat.readString(path), (name, offset) -> {
                    existing.add(name);
                    identifiers.add(new Token(name, offset));
                    if (SrgNames.parameter(name) && mappings.containsKey(name)) parameters.add(new Token(name, offset));
                    return name;
                });
                Map<String, Set<String>> neighbors = new TreeMap<>();
                Map<String, Set<String>> forbidden = new HashMap<>();
                parameters.forEach(p -> neighbors.computeIfAbsent(p.name(), k -> new HashSet<>()));
                new TreeScanner<Void, Void>() {
                    private void scope(Tree tree) {
                        long start = positions.getStartPosition(unit, tree), end = positions.getEndPosition(unit, tree);
                        Set<String> names = new HashSet<>();
                        parameters.stream().filter(p -> start <= p.offset() && p.offset() < end).forEach(p -> names.add(p.name()));
                        Set<String> scopeNames = new HashSet<>();
                        identifiers.stream().filter(p -> start <= p.offset() && p.offset() < end).forEach(p -> scopeNames.add(p.name()));
                        for (String name : names) {
                            neighbors.get(name).addAll(names);
                            forbidden.computeIfAbsent(name, k -> new HashSet<>()).addAll(scopeNames);
                        }
                    }
                    @Override public Void visitMethod(MethodTree method, Void unused) {
                        scope(method);
                        return super.visitMethod(method, unused);
                    }
                    @Override public Void visitLambdaExpression(LambdaExpressionTree lambda, Void unused) {
                        scope(lambda);
                        return super.visitLambdaExpression(lambda, unused);
                    }
                } .scan(unit, null);
                Map<String, String> assigned = new HashMap<>();
                neighbors.forEach((original, adjacent) -> {
                    String candidate = mappings.get(original);
                    while (forbidden.getOrDefault(original, existing).contains(candidate) || adjacent.stream().map(assigned::get).collect(Collectors.toList()).contains(candidate)) candidate += "$";
                    assigned.put(original, candidate);
                });
                result.put(path, assigned);
            }
        }
        java.util.List<javax.tools.Diagnostic<? extends javax.tools.JavaFileObject>> errors = diagnostics.getDiagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR).collect(Collectors.toList());
        if (!errors.isEmpty()) throw new IOException("Cannot analyze parameter scopes: " + errors.get(0).getMessage(Locale.ROOT));
        return result;
    }
}
