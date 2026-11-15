package com.github.yarnpatcher;

import java.util.stream.Collectors;

import org.openrewrite.*;
import org.openrewrite.java.*;
import org.openrewrite.java.style.ImportLayoutStyle;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.style.NamedStyles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

final class YarnImportOrganizer {
    static int organize(Path input, Path output, Collection<Path> classpath, Consumer<String> progress) throws IOException {
        SourceInstaller.requireDisjoint(input, output);
        List<Path> files;
        try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(input)) {
            files = paths.filter(Files::isRegularFile).sorted().collect(Collectors.toList());
        }
        List<Path> java = files.stream().filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        if (java.isEmpty()) throw new IOException("No Java sources to organize in " + input);
            progress.accept("Organizing imports in " + java.size() + " Java files");
            List<Throwable> errors = new ArrayList<>();
            org.openrewrite.InMemoryExecutionContext ctx = new InMemoryExecutionContext(errors::add);
            org.openrewrite.java.JavaParser parser = JavaParser.fromJavaVersion().classpath(classpath).build();
            org.openrewrite.java.style.ImportLayoutStyle layout = ImportLayoutStyle.builder().classCountToUseStarImport(Integer.MAX_VALUE).nameCountToUseStarImport(Integer.MAX_VALUE)
                .importAllOthers().blankLine().importStaticAllOthers().build();
            List<SourceFile> parsed;
            try (java.util.stream.Stream<org.openrewrite.SourceFile> stream = parser.parse(java, input, ctx)) {
                parsed = stream.collect(Collectors.toList());
            }
        Map<String, Set<String>> memberTypes = new HashMap<>();
        Map<String, Set<String>> packageTypes = new HashMap<>();
        JavaIsoVisitor<ExecutionContext> collect = new JavaIsoVisitor<ExecutionContext>() {
            @Override public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration declaration, ExecutionContext context) {
                org.openrewrite.java.tree.JavaType.FullyQualified type = declaration.getType();
                if (type != null && type.getOwningClass() != null)
                    memberTypes.computeIfAbsent(type.getOwningClass().getFullyQualifiedName(), k -> new HashSet<>()).add(declaration.getSimpleName());
                else if (type != null) packageTypes.computeIfAbsent(type.getPackageName(), k -> new HashSet<>()).add(declaration.getSimpleName());
                return super.visitClassDeclaration(declaration, context);
            }
        };
        parsed.stream().filter(J.CompilationUnit.class::isInstance).forEach(s -> collect.visit(s, ctx));
        SourceInstaller.deleteTree(output);
        Files.createDirectories(output);
        int count = 0;
for (org.openrewrite.SourceFile source : parsed) {
        if (!(source instanceof J.CompilationUnit) || source.getMarkers().findFirst(ParseExceptionResult.class).isPresent())
                throw new IOException("Cannot organize imports in " + source.getSourcePath());
            J.CompilationUnit cu = (J.CompilationUnit) source;
            cu = cu.withMarkers(cu.getMarkers().add(new NamedStyles(Tree.randomId(), "yarnpatcher-imports", YarnPatcherPlugin.NAME + " imports", "Explicit imports", Jdk8Compat.set(), Jdk8Compat.list(layout))));
            Set<String> shadowed = new HashSet<>();
            Set<String> valueNames = new HashSet<>();
            new JavaIsoVisitor<ExecutionContext>() {
                @Override public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration declaration, ExecutionContext context) {
                    inheritedNames(declaration.getType(), memberTypes, shadowed, new HashSet<>());
                    inheritedValues(declaration.getType(), valueNames, new HashSet<>());
                    return super.visitClassDeclaration(declaration, context);
                }
                @Override public J.VariableDeclarations.NamedVariable visitVariable(J.VariableDeclarations.NamedVariable variable, ExecutionContext context) {
                    valueNames.add(variable.getSimpleName());
                    return super.visitVariable(variable, context);
                }
            } .visit(cu, ctx);
            Map<UUID, J.FieldAccess> protectedTypes = new HashMap<>();
            new JavaIsoVisitor<ExecutionContext>() {
                @Override public J.FieldAccess visitFieldAccess(J.FieldAccess access, ExecutionContext context) {
                    Object parent = getCursor().getParentTreeCursor().getValue();
                    boolean qualifier = parent instanceof J.MemberReference && ((J.MemberReference) parent).getContaining() == access
                                        || parent instanceof J.MethodInvocation && ((J.MethodInvocation) parent).getSelect() == access
                                        || parent instanceof J.FieldAccess && ((J.FieldAccess) parent).getTarget() == access;
                    if (access.getName().getFieldType() == null && access.getType() instanceof JavaType.FullyQualified
                            && (shadowed.contains(access.getSimpleName()) || qualifier && valueNames.contains(access.getSimpleName())))
                        protectedTypes.put(access.getName().getId(), access);
                    return super.visitFieldAccess(access, context);
                }
            } .visit(cu, ctx);
            cu = (J.CompilationUnit) new ShortenFullyQualifiedTypeReferences().getVisitor().visitNonNull(cu, ctx);
            cu = (J.CompilationUnit) new JavaVisitor<ExecutionContext>() {
                @Override public J visitIdentifier(J.Identifier identifier, ExecutionContext context) {
                    org.openrewrite.java.tree.J.FieldAccess original = protectedTypes.get(identifier.getId());
                    if (original == null) return identifier;
                    if (getCursor().getParentTreeCursor().getValue() instanceof J.FieldAccess && ((J.FieldAccess) getCursor().getParentTreeCursor().getValue()).getName() == identifier) return identifier;
                    return original.withPrefix(identifier.getPrefix());
                }
            } .visitNonNull(cu, ctx);
            Set<String> implicitConflicts = new HashSet<>(shadowed);
            String pkg = cu.getPackageDeclaration() == null ? "" : cu.getPackageDeclaration().getExpression().printTrimmed(new Cursor(null, cu));
            implicitConflicts.addAll(packageTypes.getOrDefault(pkg, Jdk8Compat.set()));
            cu = JavaLangTypeShortener.shorten(cu, implicitConflicts, ctx);
            cu = (J.CompilationUnit) new OrderImports(true).getVisitor().visitNonNull(cu, ctx);
            if (!errors.isEmpty()) throw new IOException("Import organization failed", errors.get(0));
            Path target = output.resolve(source.getSourcePath());
            Files.createDirectories(target.getParent());
            Jdk8Compat.writeString(target, cu.printAll(), StandardCharsets.UTF_8);
            if (++count % 500 == 0) progress.accept("Organized imports in " + count + " / " + java.size() + " files");
        }
        if (!errors.isEmpty()) throw new IOException("Import parsing failed", errors.get(0));
            if (count != java.size()) throw new IOException("Import organizer did not process every Java source");
for (Path file : files) if (!file.toString().endsWith(".java")) {
                        Path target = output.resolve(input.relativize(file));
                        Files.createDirectories(target.getParent());
                        Files.copy(file, target);
                    }
        return count;
    }

    private static void inheritedNames(JavaType.FullyQualified type, Map<String, Set<String>> members, Set<String> names, Set<String> visited) {
        if (type == null || !visited.add(type.getFullyQualifiedName())) return;
        names.addAll(members.getOrDefault(type.getFullyQualifiedName(), Jdk8Compat.set()));
        inheritedNames(type.getSupertype(), members, names, visited);
        for (org.openrewrite.java.tree.JavaType.FullyQualified parent : type.getInterfaces()) inheritedNames(parent, members, names, visited);
    }

    private static void inheritedValues(JavaType.FullyQualified type, Set<String> names, Set<String> visited) {
        if (type == null || !visited.add(type.getFullyQualifiedName())) return;
        type.getMembers().forEach(field -> names.add(field.getName()));
        inheritedValues(type.getSupertype(), names, visited);
        for (JavaType.FullyQualified parent : type.getInterfaces()) inheritedValues(parent, names, visited);
    }
}
