package com.github.yarnpatcher;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import javax.lang.model.element.*;
import javax.tools.*;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Compiles CSV substitutions in memory; only methods involved in actual errors or binding changes get aliases. */
final class MethodCallConflicts {
    private static final class Token {
        private final int start;
        private final int end;
        private final String original;
        Token(int start, int end, String original) {
            this.start = start;
            this.end = end;
            this.original = original;
        }
        int start() {
            return start;
        }
        int end() {
            return end;
        }
        String original() {
            return original;
        }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Token)) return false;
            Token that = (Token) other;
            return java.util.Objects.equals(start, that.start) && java.util.Objects.equals(end, that.end) && java.util.Objects.equals(original, that.original);
        }
        @Override public int hashCode() {
            return java.util.Objects.hash(start, end, original);
        }
    }
    private static final class Call {
        private final Token token;
        private final long nameStart;
        private final long nameEnd;
        private final long start;
        private final long end;
        private final boolean reference;
        private final boolean unresolved;
        Call(Token token, long nameStart, long nameEnd, long start, long end, boolean reference, boolean unresolved) {
            this.token = token;
            this.nameStart = nameStart;
            this.nameEnd = nameEnd;
            this.start = start;
            this.end = end;
            this.reference = reference;
            this.unresolved = unresolved;
        }
        Token token() {
            return token;
        }
        long nameStart() {
            return nameStart;
        }
        long nameEnd() {
            return nameEnd;
        }
        long start() {
            return start;
        }
        long end() {
            return end;
        }
        boolean reference() {
            return reference;
        }
        boolean unresolved() {
            return unresolved;
        }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Call)) return false;
            Call that = (Call) other;
            return java.util.Objects.equals(token, that.token) && java.util.Objects.equals(nameStart, that.nameStart) && java.util.Objects.equals(nameEnd, that.nameEnd) && java.util.Objects.equals(start, that.start) && java.util.Objects.equals(end, that.end) && java.util.Objects.equals(reference, that.reference) && java.util.Objects.equals(unresolved, that.unresolved);
        }
        @Override public int hashCode() {
            return java.util.Objects.hash(token, nameStart, nameEnd, start, end, reference, unresolved);
        }
    }
    private static final class Source extends SimpleJavaFileObject {
        final String text;
        final NavigableMap<Integer, Token> tokens = new TreeMap<>();
        final Map<Long, Token> endings = new HashMap<>();
        Source(Path file, Map<String, String> names) throws IOException {
            super(file.toUri(), Kind.SOURCE);
            int[] delta = {0};
            text = SrgMemberRewriter.replace(Jdk8Compat.readString(file), (original, offset) -> {
                String mapped = names.getOrDefault(original, original);
                if (names.containsKey(original)) {
                    Token token = new Token(offset + delta[0], offset + delta[0] + mapped.length(), original);
                    tokens.put(token.start(), token);
                    endings.put((long) token.end(), token);
                }
                delta[0] += mapped.length() - original.length();
                return mapped;
            });
        }
        @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return text;
        }
    }

    static void refine(List<Path> files, Map<String, String> names, Map<String, String> safe,
                       Set<String> merged, Set<String> reserved, Collection<Path> classpath, Consumer<String> progress) throws IOException {
        JavaCompiler compiler = Jdk8Compat.compiler();
        if (compiler == null) throw new IOException("A full JDK is required for method conflict analysis");
        for (int round = 1; round <= 16; round++) {
            progress.accept("Checking actual method calls with javac, round " + round);
            Set<String> conflicts = new TreeSet<>(), bindingChanges = new TreeSet<>();
            javax.tools.DiagnosticCollector<javax.tools.JavaFileObject> diagnostics = new DiagnosticCollector<JavaFileObject>();
            List<Source> sources = new ArrayList<>();
            Map<URI, Source> byUri = new HashMap<>();
            for (Path file : files) {
                Source source = new Source(file, safe);
                sources.add(source);
                byUri.put(source.toUri(), source);
            }
            try (javax.tools.StandardJavaFileManager manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
                List<String> options = new ArrayList<>(Jdk8Compat.list("-proc:none", "-encoding", "UTF-8", "-Xmaxerrs", "1000", "-Xmaxwarns", "0"));
                if (!classpath.isEmpty()) options.addAll(Jdk8Compat.list("-classpath", classpath.stream().map(Path::toString).collect(Collectors.joining(java.io.File.pathSeparator))));
                JavacTask task = (JavacTask) compiler.getTask(null, manager, diagnostics, options, null, sources);
                List<CompilationUnitTree> units = new ArrayList<>();
                task.parse().forEach(units::add);
                task.analyze();
                Trees trees = Trees.instance(task);
                SourcePositions positions = trees.getSourcePositions();
                Map<Element, String> origins = new HashMap<>();
                // Recover original SRG names of declarations after text replacement.
                for (com.sun.source.tree.CompilationUnitTree unit : units) {
                    Source source = byUri.get(unit.getSourceFile().toUri());
                    new TreePathScanner<Void, Void>() {
                        @Override public Void visitMethod(MethodTree method, Void unused) {
                            if (method.getReturnType() != null) {
                                long from = positions.getEndPosition(unit, method.getReturnType());
                                long headerEnd = method.getBody() == null ? positions.getEndPosition(unit, method) : positions.getStartPosition(unit, method.getBody());
                                if (!method.getParameters().isEmpty()) headerEnd = Math.min(headerEnd, positions.getStartPosition(unit, method.getParameters().get(0)));
                                java.util.Map.Entry<java.lang.Integer,com.github.yarnpatcher.MethodCallConflicts.Token> token = source.tokens.ceilingEntry((int) from);
                                Element element = trees.getElement(getCurrentPath());
                                if (element != null && token != null && token.getKey() < headerEnd && safe.get(token.getValue().original()).contentEquals(method.getName()))
                                    origins.put(element, token.getValue().original());
                            }
                            return super.visitMethod(method, unused);
                        }
                    } .scan(unit, null);
                }
                java.util.List<javax.tools.Diagnostic<? extends javax.tools.JavaFileObject>> errors = diagnostics.getDiagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR).collect(Collectors.toList());
                for (com.sun.source.tree.CompilationUnitTree unit : units) {
                    Source source = byUri.get(unit.getSourceFile().toUri());
                    List<Diagnostic<? extends JavaFileObject>> unitErrors = errors.stream()
                        .filter(d -> d.getSource() != null && d.getSource().toUri().equals(source.toUri())).collect(Collectors.toList());
                    List<Call> calls = new ArrayList<>();
                    new TreePathScanner<Void, Void>() {
                        private void inspect(Tree nameTree) {
                            Token token = source.endings.get(positions.getEndPosition(unit, nameTree));
                            if (token == null || !merged.contains(token.original())) return;
                            Element element = trees.getElement(getCurrentPath());
                            String actual = origins.get(element);
                            if (actual != null && !actual.equals(token.original())) bindingChanges.add(token.original());
                            calls.add(new Call(token, positions.getStartPosition(unit, nameTree), positions.getEndPosition(unit, nameTree),
                                               positions.getStartPosition(unit, getCurrentPath().getLeaf()), positions.getEndPosition(unit, getCurrentPath().getLeaf()),
                                               getCurrentPath().getLeaf() instanceof MemberReferenceTree,
                                               !(element instanceof ExecutableElement) || actual != null && !actual.equals(token.original())));
                        }
                        @Override public Void visitMethodInvocation(MethodInvocationTree method, Void unused) {
                            inspect(method.getMethodSelect());
                            return super.visitMethodInvocation(method, unused);
                        }
                        @Override public Void visitMemberReference(MemberReferenceTree reference, Void unused) {
                            inspect(reference);
                            return super.visitMemberReference(reference, unused);
                        }
                    } .scan(unit, null);
                    // javac often points at the beginning of a qualified select, rather than its name token.
                    // Prefer that exact call over unresolved references in its arguments (which can be cascading errors).
                    List<Call> eligible = calls.stream().filter(c -> safe.get(c.token().original()).equals(names.get(c.token().original()))).collect(Collectors.toList());
                    List<Call> handled = new ArrayList<>();
                    for (javax.tools.Diagnostic<? extends javax.tools.JavaFileObject> error : unitErrors) {
                        if (handled.stream().anyMatch(c -> c.start() <= error.getPosition() && error.getPosition() < c.end())) continue;
                        Optional<Call> culprit = eligible.stream().filter(c -> c.nameStart() <= error.getPosition() && error.getPosition() < c.nameEnd())
                                                 .min(Comparator.comparingLong(c -> c.nameEnd() - c.nameStart()));
                        if (!culprit.isPresent()) culprit = eligible.stream().filter(c -> c.reference() && c.unresolved()
                                && error.getStartPosition() <= c.end() && error.getEndPosition() >= c.start())
                                                                .min(Comparator.comparingLong(c -> c.end() - c.start()));
                        if (!culprit.isPresent()) culprit = eligible.stream().filter(c -> c.start() <= error.getPosition() && error.getPosition() < c.end())
                                                                .min(Comparator.comparingLong(c -> c.end() - c.start()));
                        culprit.ifPresent(c -> { conflicts.add(c.token().original()); handled.add(c); });
                    }
                }
                // Prefer calls identified directly by diagnostics. A changed inner overload can
                // instead report an error at a collector or a downstream lambda assignment.
                if (errors.isEmpty() || conflicts.isEmpty()) conflicts.addAll(bindingChanges);
                if (conflicts.isEmpty()) {
                    if (!errors.isEmpty()) throw new IOException("Unresolved method compilation errors: " + errors.stream().limit(5)
                                .map(d -> d.getSource().getName() + ":" + d.getLineNumber() + ": " + d.getMessage(Locale.ROOT)).collect(Collectors.joining("\n")));
                    progress.accept("All " + files.size() + " sources pass javac method analysis; original overload bindings preserved");
                    return;
                }
            }
            for (String original : conflicts) {
                String candidate = safe.get(original) + "$";
                while (reserved.contains(candidate)) candidate += "$";
                safe.put(original, candidate);
                reserved.add(candidate);
                progress.accept("Actual method call conflict: " + original + " -> " + candidate);
            }
        }
        throw new IOException("Method conflict analysis did not converge within 16 rounds");
    }
}
