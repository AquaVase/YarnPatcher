package com.github.yarnpatcher;

import java.util.stream.Collectors;

import org.openrewrite.*;
import org.openrewrite.java.*;
import org.openrewrite.java.tree.*;
import org.openrewrite.tree.ParseError;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import static com.github.yarnpatcher.YarnSourceRewriter.*;

/** Read-only OpenRewrite analysis. The CSV backend performs every method rename. */
final class MethodConflictNames {
    static void generate(Path input, Path csv, Path output, Collection<Path> classpath, Consumer<String> progress) throws IOException {
        Map<String, String> names = new TreeMap<>(SrgMemberRewriter.readCsv(csv));
        List<Path> sources;
        try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(input)) {
            sources = paths.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
        }
        if (sources.isEmpty()) throw new IOException("No Java sources in " + input);
        progress.accept("Analyzing method conflicts in " + sources.size() + " Java files with OpenRewrite");
        List<Throwable> errors = new ArrayList<>();
        org.openrewrite.InMemoryExecutionContext ctx = new InMemoryExecutionContext(errors::add);
        org.openrewrite.java.JavaParser parser = JavaParser.fromJavaVersion().classpath(classpath).build();
        Map<String, JavaType.FullyQualified> classes = new TreeMap<>();
        Map<String, List<JavaType.Method>> declarations = new TreeMap<>();
        Map<String, Map<String, String>> recordComponents = new TreeMap<>();
        Set<String> reserved = new HashSet<>(names.values());
        JavaIsoVisitor<ExecutionContext> visitor = new JavaIsoVisitor<ExecutionContext>() {
            @Override public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration declaration, ExecutionContext context) {
                if (declaration.getType() == null) throw new IllegalStateException("Missing type for " + declaration.getSimpleName());
                String owner = binaryName(declaration.getType());
                classes.put(owner, declaration.getType());
                declarations.put(owner, declaration.getBody().getStatements().stream().filter(J.MethodDeclaration.class::isInstance)
                                 .map(J.MethodDeclaration.class::cast).filter(m -> !m.isConstructor() && m.getMethodType() != null)
                                 .map(J.MethodDeclaration::getMethodType).collect(Collectors.toList()));
                if (declaration.getKind() == J.ClassDeclaration.Kind.Type.Record && declaration.getPrimaryConstructor() != null) {
                    Map<String, String> components = new HashMap<>();
                    for (J component : declaration.getPrimaryConstructor()) {
                        if (!(component instanceof J.VariableDeclarations)) continue;
                        J.VariableDeclarations variables = (J.VariableDeclarations) component;
                        JavaType type = variables.getTypeExpression() == null ? null : variables.getTypeExpression().getType();
                        if (type == null && !variables.getVariables().isEmpty()) type = variables.getVariables().get(0).getType();
                        if (type == null) continue;
                        String descriptor = descriptor(type);
                        for (J.VariableDeclarations.NamedVariable variable : variables.getVariables())
                            components.put(variable.getSimpleName(), descriptor);
                    }
                    recordComponents.put(owner, components);
                }
                return super.visitClassDeclaration(declaration, context);
            }
        };
        int count = 0;
        try (java.util.stream.Stream<org.openrewrite.SourceFile> parsed = parser.parse(sources, input, ctx)) {
            for (java.util.Iterator<org.openrewrite.SourceFile> iterator = parsed.iterator(); iterator.hasNext();) {
                SourceFile source = iterator.next();
                if (source instanceof ParseError || !(source instanceof J.CompilationUnit)
                        || source.getMarkers().findFirst(ParseExceptionResult.class).isPresent())
                    throw new IOException("Cannot analyze " + source.getSourcePath());
                visitor.visit(source, ctx); // Never print or save the AST.
                count++;
            }
        }
        if (!errors.isEmpty()) throw new IOException("OpenRewrite conflict analysis failed", errors.get(0));
        if (count != sources.size()) throw new IOException("OpenRewrite did not analyze every source file");
        Set<String> conflicting = new TreeSet<>();
        Set<String> merged = new HashSet<>();
        for (java.util.Map.Entry<java.lang.String,org.openrewrite.java.tree.JavaType.FullyQualified> entry : classes.entrySet()) {
            List<JavaType.Method> parents = new ArrayList<>();
            Set<String> visited = new HashSet<>();
            collect(entry.getValue().getSupertype(), parents, visited);
            for (org.openrewrite.java.tree.JavaType.FullyQualified parent : entry.getValue().getInterfaces()) collect(parent, parents, visited);
            List<JavaType.Method> own = declarations.getOrDefault(entry.getKey(), Jdk8Compat.list());
            Map<String, Set<String>> scope = new HashMap<>();
            List<JavaType.Method> visible = new ArrayList<>();
            Set<String> scopeVisited = new HashSet<>();
            for (org.openrewrite.java.tree.JavaType.FullyQualified type = entry.getValue(); type != null; type = type.getOwningClass()) collect(type, visible, scopeVisited);
            for (org.openrewrite.java.tree.JavaType.Method method : visible) if (!method.isConstructor()) scope.computeIfAbsent(names.getOrDefault(method.getName(), method.getName()), k -> new HashSet<>()).add(method.getName());
            scope.values().stream().filter(group -> group.size() > 1).forEach(merged::addAll);
            for (org.openrewrite.java.tree.JavaType.Method method : own) {
                if (!names.containsKey(method.getName())) continue;
                String target = names.get(method.getName());
                String recordComponent = recordComponents.getOrDefault(entry.getKey(), Collections.emptyMap()).get(target);
                if (recordComponent != null && parameters(method).isEmpty()
                        && !recordComponent.equals(descriptor(method.getReturnType()))) {
                    conflicting.add(method.getName());
                    progress.accept("Java record accessor conflict: " + method.getName() + " -> " + target + "$");
                    continue;
                }
                for (org.openrewrite.java.tree.JavaType.Method parent : parents) {
                    if (parent.hasFlags(Flag.Private) || method.getName().equals(parent.getName())) continue;
                    if (!target.equals(names.getOrDefault(parent.getName(), parent.getName())) || !parameters(method).equals(parameters(parent))) continue;
                    if (method.hasFlags(Flag.Private)
                            || method.hasFlags(Flag.Static) != parent.hasFlags(Flag.Static)
                            || !TypeUtils.isAssignableTo(parent.getReturnType(), method.getReturnType())
                            || method.hasFlags(Flag.Static) && classes.containsKey(binaryName(parent.getDeclaringType()))
                            && !descriptor(parent.getReturnType()).equals(descriptor(method.getReturnType()))) {
                        conflicting.add(method.getName());
                        break;
                    }
                }
                // Duplicate declarations are illegal even when their return types differ.
                for (org.openrewrite.java.tree.JavaType.Method other : own) {
                    if (method == other || method.getName().equals(other.getName())) continue;
                    if (target.equals(names.getOrDefault(other.getName(), other.getName())) && parameters(method).equals(parameters(other))
                            && (!names.containsKey(other.getName()) || method.getName().compareTo(other.getName()) > 0)) conflicting.add(method.getName());
                }
            }
            own.forEach(m -> reserved.add(names.getOrDefault(m.getName(), m.getName())));
            parents.forEach(m -> reserved.add(names.getOrDefault(m.getName(), m.getName())));
        }
        Map<String, String> safe = new TreeMap<>(names);
        for (String original : conflicting) {
            String candidate = names.get(original) + "$";
            while (reserved.contains(candidate)) candidate += "$";
            safe.put(original, candidate);
            reserved.add(candidate);
            progress.accept("Java declaration conflict: " + original + " -> " + candidate);
        }
        classes.clear();
        declarations.clear();
        parser.reset();
        MethodCallConflicts.refine(sources, names, safe, merged, reserved, classpath, progress);
        Files.createDirectories(output);
        StringBuilder methods = new StringBuilder("searge,name,side,desc\n");
        StringBuilder conflicts = new StringBuilder("source,yarn,safe\n");
        safe.forEach((original, target) -> {
            methods.append(original).append(',').append(target).append(",2,\n");
            if (!target.equals(names.get(original))) conflicts.append(original).append(',').append(names.get(original)).append(',').append(target).append('\n');
        });
        Jdk8Compat.writeString(output.resolve("methods.csv"), methods);
        Jdk8Compat.writeString(output.resolve("method-conflicts.csv"), conflicts);
        progress.accept("Generated safe method CSV; " + safe.entrySet().stream().filter(e -> !e.getValue().equals(names.get(e.getKey()))).count() + " method families use a $ suffix");
    }

    private static void collect(JavaType.FullyQualified type, List<JavaType.Method> methods, Set<String> visited) {
        if (type == null || !visited.add(binaryName(type))) return;
        methods.addAll(type.getMethods());
        collect(type.getSupertype(), methods, visited);
        for (org.openrewrite.java.tree.JavaType.FullyQualified parent : type.getInterfaces()) collect(parent, methods, visited);
    }

    private static String parameters(JavaType.Method method) {
        return method.getParameterTypes().stream().map(YarnSourceRewriter::descriptor).reduce("", String::concat);
    }
}
