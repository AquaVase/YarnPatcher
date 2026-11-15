package com.github.yarnpatcher;

import java.util.stream.Collectors;

import org.openrewrite.*;
import org.openrewrite.java.*;
import org.openrewrite.java.tree.*;
import java.util.*;
import java.util.function.Consumer;
import static com.github.yarnpatcher.YarnSourceRewriter.*;

/** Restores bridges removed by decompilation and prevents accidental Java overrides. */
final class SourceCompatibility {
    static final class Bridge {
        private final String name;
        private final J.MethodDeclaration method;
        private final J.Block body;
        Bridge(String name, J.MethodDeclaration method, J.Block body) {
            this.name = name;
            this.method = method;
            this.body = body;
        }
        String name() {
            return name;
        }
        J.MethodDeclaration method() {
            return method;
        }
        J.Block body() {
            return body;
        }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Bridge)) return false;
            Bridge that = (Bridge) other;
            return java.util.Objects.equals(name, that.name) && java.util.Objects.equals(method, that.method) && java.util.Objects.equals(body, that.body);
        }
        @Override public int hashCode() {
            return java.util.Objects.hash(name, method, body);
        }
    }
    final Map<String, List<Bridge>> bridges = new HashMap<>();
    private final Map<String, List<J.MethodDeclaration>> methods = new HashMap<>();
    private final Map<String, J.ClassDeclaration> classes = new HashMap<>();
    private final Members names;
    private final MappingIndex index;
    private final JavaParser parser = JavaParser.fromJavaVersion().build();

    SourceCompatibility(List<SourceFile> sources, Members names, MappingIndex index, Consumer<String> progress) {
        this.names = names;
        this.index = index;
        org.openrewrite.InMemoryExecutionContext ctx = new InMemoryExecutionContext();
        JavaIsoVisitor<ExecutionContext> collect = new JavaIsoVisitor<ExecutionContext>() {
            @Override public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration c, ExecutionContext context) {
                if (c.getType() != null) {
                    String owner = binaryName(c.getType());
                    classes.put(owner, c);
                    methods.put(owner, c.getBody().getStatements().stream().filter(J.MethodDeclaration.class::isInstance)
                                .map(J.MethodDeclaration.class::cast).filter(m -> !m.isConstructor() && m.getMethodType() != null).collect(Collectors.toList()));
                }
                return super.visitClassDeclaration(c, context);
            }
        };
        sources.forEach(s -> collect.visit(s, ctx));
        // JVM permits return-type-only distinctions and static generic signatures Java cannot express.
        for (java.util.Map.Entry<java.lang.String,org.openrewrite.java.tree.J.ClassDeclaration> entry : classes.entrySet()) {
            org.openrewrite.java.tree.JavaType.FullyQualified type = entry.getValue().getType();
            List<JavaType.Method> parents = ancestors(type);
            for (org.openrewrite.java.tree.J.MethodDeclaration m : methods.getOrDefault(entry.getKey(), Jdk8Compat.list())) {
                org.openrewrite.java.tree.JavaType.Method method = m.getMethodType();
                String mapped = name(method);
                for (org.openrewrite.java.tree.JavaType.Method parent : parents) {
                    if (parent.hasFlags(Flag.Private) || method.getName().equals(parent.getName())) continue;
                    if (mapped.equals(name(parent)) && parameters(method).equals(parameters(parent))
                            && (method.hasFlags(Flag.Private)
                                || !TypeUtils.isAssignableTo(parent.getReturnType(), method.getReturnType())
                                || method.hasFlags(Flag.Static) && index.classes.containsKey(binaryName(parent.getDeclaringType()))
                                && !descriptor(parent.getReturnType()).equals(descriptor(method.getReturnType())))) {
                        String alias = mapped + "$yarnpatcher$" + method.getName();
                        index.methods.put(key(method), alias);
                        progress.accept("Java signature compatibility alias: " + entry.getKey() + "." + mapped + " -> " + alias);
                        break;
                    }
                }
            }
        }
        // A decompiler elides synthetic forwarding methods. Recreate their source equivalents.
        for (java.util.Map.Entry<java.lang.String,org.openrewrite.java.tree.J.ClassDeclaration> entry : classes.entrySet()) {
            org.openrewrite.java.tree.JavaType.FullyQualified type = entry.getValue().getType();
            List<JavaType.Method> parents = ancestors(type);
            List<J.MethodDeclaration> implementations = new ArrayList<>(methods.getOrDefault(entry.getKey(), Jdk8Compat.list()));
            inheritedMethods(type.getSupertype(), implementations, new HashSet<>());
            Set<String> added = new HashSet<>();
            for (org.openrewrite.java.tree.JavaType.Method parent : parents) {
                if (parent.hasFlags(Flag.Static) || parent.hasFlags(Flag.Private)) continue;
                for (org.openrewrite.java.tree.J.MethodDeclaration m : implementations) {
                    org.openrewrite.java.tree.JavaType.Method impl = m.getMethodType();
                    if (m.getBody() == null || impl.hasFlags(Flag.Static) || impl.hasFlags(Flag.Private)) continue;
                    if (!parent.getName().equals(impl.getName()) || !compatibleParameters(parent, impl)) continue;
                    String required = name(parent), actual = name(impl);
                    if (required.equals(actual) || !added.add(required + parameters(impl))) break;
                    bridges.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(new Bridge(required, m, body(m, actual)));
                    break;
                }
            }
        }
        progress.accept("Prepared " + bridges.values().stream().mapToInt(List::size).sum() + " source bridge methods");
    }

    private J.Block body(J.MethodDeclaration method, String target) {
        String args = method.getParameters().stream().filter(J.VariableDeclarations.class::isInstance)
                      .map(J.VariableDeclarations.class::cast).flatMap(v -> v.getVariables().stream())
                      .map(J.VariableDeclarations.NamedVariable::getSimpleName).reduce((a, b) -> a + ", " + b).orElse("");
        String statement = (method.getMethodType().getReturnType() == JavaType.Primitive.Void ? "" : "return ")
                           + "this." + target + "(" + args + ");";
        String source = "class Bridge { Object bridge() { " + statement + " } }";
        SourceFile parsed = parser.reset().parse(source).findFirst().orElseThrow(() -> new IllegalStateException("Expected a nonempty result"));
        if (!(parsed instanceof J.CompilationUnit)) throw new IllegalStateException("Cannot parse generated bridge: " + source + " / " + parsed.getMarkers().findFirst(ParseExceptionResult.class).map(ParseExceptionResult::getMessage).orElse("unknown error"));
        return ((J.MethodDeclaration) ((J.CompilationUnit) parsed).getClasses().get(0).getBody().getStatements().get(0)).getBody();
    }

    private boolean compatibleParameters(JavaType.Method a, JavaType.Method b) {
        if (a.getParameterTypes().size() != b.getParameterTypes().size()) return false;
        for (int i = 0; i < a.getParameterTypes().size(); i++) {
            JavaType left = a.getParameterTypes().get(i), right = b.getParameterTypes().get(i);
            if (!descriptor(left).equals(descriptor(right)) && !(left instanceof JavaType.GenericTypeVariable)) return false;
        }
        return true;
    }

    private void inheritedMethods(JavaType.FullyQualified type, List<J.MethodDeclaration> out, Set<String> seen) {
        if (type == null || !seen.add(binaryName(type))) return;
        out.addAll(methods.getOrDefault(binaryName(type), Jdk8Compat.list()));
        inheritedMethods(type.getSupertype(), out, seen);
    }

    private List<JavaType.Method> ancestors(JavaType.FullyQualified type) {
        List<JavaType.Method> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        gather(type.getSupertype(), result, seen);
        for (org.openrewrite.java.tree.JavaType.FullyQualified parent : type.getInterfaces()) gather(parent, result, seen);
        return result;
    }

    private void gather(JavaType.FullyQualified type, List<JavaType.Method> out, Set<String> seen) {
        if (type == null || !seen.add(binaryName(type))) return;
        out.addAll(type.getMethods());
        gather(type.getSupertype(), out, seen);
        for (org.openrewrite.java.tree.JavaType.FullyQualified parent : type.getInterfaces()) gather(parent, out, seen);
    }

    private String name(JavaType.Method method) {
        return Optional.ofNullable(names.methodName(method)).orElse(method.getName());
    }
    private String parameters(JavaType.Method method) {
        return method.getParameterTypes().stream().map(YarnSourceRewriter::descriptor).reduce("", String::concat);
    }
    private MappingIndex.Member key(JavaType.Method method) {
        return new MappingIndex.Member(binaryName(method.getDeclaringType()), method.getName(), "(" + parameters(method) + ")" + descriptor(method.getReturnType()));
    }
}
