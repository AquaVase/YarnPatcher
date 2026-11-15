package com.github.yarnpatcher;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import net.minecraftforge.srgutils.IMappingFile;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.lang.model.util.*;
import javax.tools.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

final class ExistingStaticImports {
    private static final class Edit {
        private final int start;
        private final int end;
        private final String text;
        Edit(int start, int end, String text) {
            this.start = start;
            this.end = end;
            this.text = text;
        }
        int start() {
            return start;
        }
        int end() {
            return end;
        }
        String text() {
            return text;
        }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Edit)) return false;
            Edit that = (Edit) other;
            return java.util.Objects.equals(start, that.start) && java.util.Objects.equals(end, that.end) && java.util.Objects.equals(text, that.text);
        }
        @Override public int hashCode() {
            return java.util.Objects.hash(start, end, text);
        }
    }

    static void repair(Path original, Path normalized, Path mappings, Collection<Path> classpath) throws IOException {
        List<Path> files;
        try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(original)) {
            files = paths.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
        }
        // This inexpensive scan only decides whether attribution is needed, never edits text.
        boolean hasImports = false;
        for (Path file : files) if (Jdk8Compat.readString(file).matches("(?s).*\\bimport\\s+static\\b.*")) {
                hasImports = true;
                break;
            }
        if (!hasImports) return;
        IMappingFile map = IMappingFile.load(mappings.toFile());
        JavaCompiler compiler = Jdk8Compat.compiler();
        if (compiler == null) throw new IOException("A full JDK is required to resolve static imports");
        javax.tools.DiagnosticCollector<javax.tools.JavaFileObject> diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (javax.tools.StandardJavaFileManager manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            List<String> options = new ArrayList<>(Jdk8Compat.list("-proc:none", "-encoding", "UTF-8", "-Xmaxerrs", "1000"));
            options.addAll(Jdk8Compat.list("-classpath", classpath.stream().map(Path::toString).collect(Collectors.joining(java.io.File.pathSeparator))));
            JavacTask task = (JavacTask) compiler.getTask(null, manager, diagnostics, options, null,
                Jdk8Compat.sourceFiles(manager, files));
            List<CompilationUnitTree> units = new ArrayList<>();
            task.parse().forEach(units::add);
            task.analyze();
            java.util.List<javax.tools.Diagnostic<? extends javax.tools.JavaFileObject>> errors = diagnostics.getDiagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR).collect(Collectors.toList());
            if (!errors.isEmpty()) throw new IOException("Existing named sources must compile before static-import mapping: "
                        + errors.stream().limit(5).map(d -> d.getSource() + ":" + d.getLineNumber() + ": " + d.getMessage(Locale.ROOT)).collect(Collectors.joining("\n")));
            Trees trees = Trees.instance(task);
            Elements elements = task.getElements();
            Types types = task.getTypes();
            Map<Path, Map<String, String>> importsByFile = new HashMap<>();
            for (com.sun.source.tree.CompilationUnitTree unit : units) {
                Map<String, String> replacements = new HashMap<>();
                for (ImportTree imp : unit.getImports()) {
                    if (!imp.isStatic() || !(imp.getQualifiedIdentifier() instanceof MemberSelectTree)
                            || ((MemberSelectTree) imp.getQualifiedIdentifier()).getIdentifier().contentEquals("*")) continue;
                    TreePath ownerPath = TreePath.getPath(unit, ((MemberSelectTree) imp.getQualifiedIdentifier()).getExpression());
                    if (!(trees.getElement(ownerPath) instanceof TypeElement))
                        throw new IOException("Cannot resolve static import: " + imp);
                    String ownerName = elements.getBinaryName(((TypeElement) trees.getElement(ownerPath))).toString().replace('.', '/');
                    String targetOwner = map.remapClass(ownerName).replace('/', '.').replace('$', '.');
                    String name = ((MemberSelectTree) imp.getQualifiedIdentifier()).getIdentifier().toString();
                    Set<String> targets = new TreeSet<>();
                    for (Element member : elements.getAllMembers(((TypeElement) trees.getElement(ownerPath)))) {
                        if (!member.getSimpleName().contentEquals(name) || !member.getModifiers().contains(Modifier.STATIC)) continue;
                        TypeElement declaring = (TypeElement) member.getEnclosingElement();
                        net.minecraftforge.srgutils.IMappingFile.IClass cls = map.getClass(elements.getBinaryName(declaring).toString().replace('.', '/'));
                        String target = name;
                        if (member instanceof ExecutableElement && cls != null) {
                            String desc = ((ExecutableElement) member).getParameters().stream().map(p -> descriptor(p.asType(), types, elements)).collect(Collectors.joining("", "(", ")"))
                            + descriptor(((ExecutableElement) member).getReturnType(), types, elements);
                            target = cls.remapMethod(name, desc);
                        } else if (member instanceof VariableElement && cls != null) target = cls.remapField(name);
                        else if (member instanceof TypeElement) {
                            String full = map.remapClass(elements.getBinaryName(((TypeElement) member)).toString().replace('.', '/')).replace('/', '.').replace('$', '.');
                            targets.add("import static " + full + ";");
                            continue;
                        }
                        targets.add("import static " + targetOwner + "." + target + ";");
                    }
                    if (targets.isEmpty()) throw new IOException("Cannot resolve members of static import: " + imp);
                    replacements.put(((MemberSelectTree) imp.getQualifiedIdentifier()).toString(), String.join("\n", targets));
                }
                if (!replacements.isEmpty()) importsByFile.put(java.nio.file.Paths.get(unit.getSourceFile().toUri()).toAbsolutePath().normalize(), replacements);
            }
            // Parse the normalized files for exact import spans, preserving comments and all other text.
            for (java.util.Map.Entry<java.nio.file.Path,java.util.Map<java.lang.String,java.lang.String>> entry : importsByFile.entrySet()) {
                Path relative = original.toAbsolutePath().normalize().relativize(entry.getKey());
                String classPath = relative.toString().replace('\\', '/').replaceFirst("\\.java$", "");
                Path target = normalized.resolve(map.remapClass(classPath) + ".java");
                if (!Files.exists(target)) throw new IOException("Missing normalized source for " + relative);
                String text = Jdk8Compat.readString(target);
                com.sun.source.util.JavacTask parse = (JavacTask) compiler.getTask(null, manager, null, Jdk8Compat.list("-proc:none"), null,
                    Jdk8Compat.sourceFiles(manager, Jdk8Compat.list(target)));
                CompilationUnitTree unit = parse.parse().iterator().next();
                SourcePositions positions = Trees.instance(parse).getSourcePositions();
                List<Edit> edits = new ArrayList<>();
                for (ImportTree imp : unit.getImports()) {
                    String replacement = imp.isStatic() ? entry.getValue().get(imp.getQualifiedIdentifier().toString()) : null;
                    if (replacement != null) edits.add(new Edit((int) positions.getStartPosition(unit, imp), (int) positions.getEndPosition(unit, imp), replacement));
                }
                StringBuilder out = new StringBuilder(text);
                edits.sort(Comparator.comparingInt(Edit::start).reversed());
                for (Edit edit : edits) out.replace(edit.start(), edit.end(), edit.text());
                Jdk8Compat.writeString(target, out);
            }
        }
    }

    private static String descriptor(TypeMirror type, Types types, Elements elements) {
        switch (type.getKind()) {
        case BOOLEAN:
            return "Z";
        case BYTE:
            return "B";
        case SHORT:
            return "S";
        case INT:
            return "I";
        case LONG:
            return "J";
        case CHAR:
            return "C";
        case FLOAT:
            return "F";
        case DOUBLE:
            return "D";
        case VOID:
            return "V";
        case ARRAY:
            return "[" + descriptor(((ArrayType) type).getComponentType(), types, elements);
        case DECLARED:
            return "L" + elements.getBinaryName((TypeElement) ((DeclaredType) type).asElement()).toString().replace('.', '/') + ";";
        case TYPEVAR:
        case WILDCARD:
        case INTERSECTION:
            return descriptor(types.erasure(type), types, elements);
        default:
            throw new IllegalArgumentException("Unsupported JVM type: " + type);
        }
    }
}
