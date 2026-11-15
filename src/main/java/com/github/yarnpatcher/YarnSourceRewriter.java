package com.github.yarnpatcher;

import org.openrewrite.*;
import org.openrewrite.java.*;
import org.openrewrite.java.tree.*;
import org.openrewrite.java.search.FindMissingTypes;
import org.openrewrite.tree.ParseError;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import java.util.function.Consumer;

final class YarnSourceRewriter {
    static int rewrite(Path input, Path output, Path mappings, Collection<Path> classpath) throws IOException {
        return rewrite(input, output, mappings, classpath, message -> { });
    }

    static int rewrite(Path input, Path output, Path mappings, Collection<Path> classpath, Consumer<String> progress) throws IOException {
        return rewrite(input, output, mappings, classpath, progress, true);
    }

    static int rewrite(Path input, Path output, Path mappings, Collection<Path> classpath, Consumer<String> progress, boolean rewriteTypes) throws IOException {
        MappingIndex index = MappingIndex.read(mappings, "srg", "yarn");
        Map<String, String> classes = new HashMap<>();
        index.classes.forEach((from, to) -> classes.put(from.replace('/', '.').replace('$', '.'), to.replace('/', '.').replace('$', '.')));
        Set<String> methodOwners = index.methods.keySet().stream().map(MappingIndex.Member::owner).collect(Collectors.toSet());
        List<Path> sources;
        try (Stream<Path> paths = Files.walk(input)) {
            sources = paths.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
        }
        if (sources.isEmpty()) throw new IOException("No Java sources in " + input);
        progress.accept("Parsing " + sources.size() + " source files with OpenRewrite (" + (rewriteTypes ? "types" : "methods") + ")");
        List<Throwable> errors = new ArrayList<>();
        ExecutionContext ctx = new InMemoryExecutionContext(errors::add);
        JavaParser parser = JavaParser.fromJavaVersion().classpath(classpath).build();
        List<SourceFile> parsed;
        try (Stream<SourceFile> stream = parser.parse(sources, input, ctx)) {
            parsed = stream.collect(Collectors.toList());
        }
        if (!errors.isEmpty()) throw new IOException("OpenRewrite parsing failed", errors.get(0));
        if (parsed.size() != sources.size()) throw new IOException("OpenRewrite did not parse every source file");
        Members memberNames = new Members(index, methodOwners);
        SourceCompatibility compatibility = index.methods.isEmpty() ? null : new SourceCompatibility(parsed, memberNames, index, progress);
        Members memberRewriter = new Members(index, methodOwners, compatibility);
        Files.createDirectories(output);
        Set<Path> written = new HashSet<>();
        for (SourceFile source : parsed) {
            if (source instanceof ParseError || source.getMarkers().findFirst(ParseExceptionResult.class).isPresent()
                    || !(source instanceof J.CompilationUnit)) throw new IOException("Cannot rewrite " + source.getSourcePath() + ": " + source.getMarkers().findFirst(ParseExceptionResult.class).map(ParseExceptionResult::getMessage).orElse(source.printAll()));
            J.CompilationUnit cu = (J.CompilationUnit) source;
            cu = (J.CompilationUnit) new JavaIsoVisitor<ExecutionContext>() {
                @Override public J.NewClass visitNewClass(J.NewClass creation, ExecutionContext context) {
                    J.NewClass n = super.visitNewClass(creation, context);
                    if (n.getClazz() instanceof J.ParameterizedType && ((J.ParameterizedType) n.getClazz()).getClazz() instanceof J.Identifier
                            && !(((J.Identifier) ((J.ParameterizedType) n.getClazz()).getClazz()).getType() instanceof JavaType.Class) && n.getConstructorType() != null) {
                        JavaType.FullyQualified raw = n.getConstructorType().getDeclaringType();
                        if (raw instanceof JavaType.Parameterized) raw = ((JavaType.Parameterized) raw).getType();
                        if (raw instanceof JavaType.Class) n = n.withClazz(((J.ParameterizedType) n.getClazz()).withClazz(((J.Identifier) ((J.ParameterizedType) n.getClazz()).getClazz()).withType(raw)));
                    }
                    return n;
                }
            } .visitNonNull(cu, ctx);
            java.util.List<org.openrewrite.java.search.FindMissingTypes.MissingTypeResult> missing = FindMissingTypes.findMissingTypes(cu, false);
            if (!missing.isEmpty()) throw new IOException("Missing type attribution in " + source.getSourcePath() + ": "
                        + missing.get(0).getMessage() + " at " + missing.get(0).getPrintedTree());
            String mappedTop = null;
            for (J.ClassDeclaration top : cu.getClasses()) {
                if (top.getType() == null) continue;
                String mapped = classes.get(top.getType().getFullyQualifiedName().replace('$', '.'));
                if (mapped != null) {
                    String pkg = mapped.substring(0, Math.max(0, mapped.lastIndexOf('.')));
                    if (mappedTop != null && !mappedTop.substring(0, Math.max(0, mappedTop.lastIndexOf('.'))).equals(pkg))
                        throw new IOException("Top level classes map to different packages: " + source.getSourcePath());
                    if (mappedTop == null || top.hasModifier(J.Modifier.Type.Public)) mappedTop = mapped;
                }
            }
            J.CompilationUnit result = (J.CompilationUnit) memberRewriter.visitNonNull(cu, ctx);
            if (rewriteTypes) result = (J.CompilationUnit) new Names(classes).visitNonNull(result, ctx);
            if (mappedTop != null) {
                int dot = mappedTop.lastIndexOf('.');
                String pkg = dot < 0 ? "" : mappedTop.substring(0, dot);
                if (pkg.isEmpty()) result = result.withPackageDeclaration(null);
                else if (result.getPackageDeclaration() != null) {
                    result = result.withPackageDeclaration(result.getPackageDeclaration().withExpression(TypeTree.build(pkg).withPrefix(Space.SINGLE_SPACE)));
                } else throw new IOException("Cannot move a default-package source into a named package: " + source.getSourcePath());
                result = result.withSourcePath(java.nio.file.Paths.get(mappedTop.replace('.', '/') + ".java"));
            }
            result = result.withImports(result.getImports().stream().filter(J.Import::isStatic).collect(Collectors.toList()));
            if (!errors.isEmpty()) throw new IOException("OpenRewrite transformation failed", errors.get(0));
            Path target = output.resolve(result.getSourcePath()).normalize();
            if (!target.startsWith(output.normalize()) || !written.add(target)) throw new IOException("Invalid or colliding rewritten source path: " + target);
            Files.createDirectories(target.getParent());
            Jdk8Compat.writeString(target, result.printAll(), StandardCharsets.UTF_8);
            if (written.size() % 100 == 0) progress.accept("Rewrote " + written.size() + " / " + sources.size() + " Java files");
        }
        try (Stream<Path> paths = Files.walk(input)) {
            for (Path path : paths.filter(Files::isRegularFile).filter(p -> !p.toString().endsWith(".java")).collect(Collectors.toList())) {
                Path target = output.resolve(input.relativize(path));
                Files.createDirectories(target.getParent());
                Files.copy(path, target);
            }
        }
        return written.size();
    }

    private static final class Names extends JavaVisitor<ExecutionContext> {
        private final Map<String, String> classes;
        private final Set<String> targetSimpleNames;
        Names(Map<String, String> classes) {
            this.classes = classes;
            this.targetSimpleNames = classes.values().stream().map(n -> n.substring(n.lastIndexOf('.') + 1)).collect(Collectors.toSet());
        }
        private List<J.Modifier> publicAccess(List<J.Modifier> modifiers) {
            if (J.Modifier.hasModifier(modifiers, J.Modifier.Type.Private) || J.Modifier.hasModifier(modifiers, J.Modifier.Type.Public)) return modifiers;
            List<J.Modifier> result = new ArrayList<>();
            boolean replaced = false;
            for (J.Modifier modifier : modifiers) {
                if (modifier.getType() == J.Modifier.Type.Protected) {
                    result.add(modifier.withType(J.Modifier.Type.Public));
                    replaced = true;
                } else result.add(modifier);
            }
            if (!replaced) result.add(0, new J.Modifier(Tree.randomId(), Space.EMPTY, org.openrewrite.marker.Markers.EMPTY, null, J.Modifier.Type.Public, Jdk8Compat.list()));
            return result;
        }
        private String mapped(JavaType.FullyQualified type) {
            String name = type.getFullyQualifiedName().replace('$', '.');
            return classes.getOrDefault(name, name);
        }
        private String typeName(JavaType type, String spelling) {
            JavaType.FullyQualified fq = TypeUtils.asFullyQualified(type);
            if (fq == null) return null;
            String full = fq.getFullyQualifiedName();
            String simple = full.substring(Math.max(full.lastIndexOf('.'), full.lastIndexOf('$')) + 1);
            String sourceSimple = simple.replaceFirst("^[0-9]+", "");
            if (!spelling.equals(simple) && !spelling.equals(sourceSimple) && !spelling.equals(fq.getClassName().replace('$', '.'))
                    && !spelling.equals(full.replace('$', '.'))) return null;
            String target = mapped(fq);
            return full.matches(".*\\$[0-9]+[A-Za-z_].*") ? target.substring(target.lastIndexOf('.') + 1) : target;
        }
        @Override public J visitPackage(J.Package pkg, ExecutionContext ctx) {
            return pkg.withAnnotations(pkg.getAnnotations().stream().map(a -> (J.Annotation) visit(a, ctx)).collect(Collectors.toList()));
        }
        @Override public J visitImport(J.Import imp, ExecutionContext ctx) {
            if (!imp.isStatic()) return imp;
            JavaType.FullyQualified owner = TypeUtils.asFullyQualified(imp.getQualid().getTarget().getType());
            if (owner == null) throw new IllegalStateException("Unresolved static import: " + imp);
            return imp.withQualid(imp.getQualid().withTarget(TypeTree.build(mapped(owner))));
        }
        @Override public J visitIdentifier(J.Identifier id, ExecutionContext ctx) {
            if (id.getType() instanceof JavaType.GenericTypeVariable && id.getSimpleName().equals(((JavaType.GenericTypeVariable) id.getType()).getName())
                    && targetSimpleNames.contains(((JavaType.GenericTypeVariable) id.getType()).getName())) return id.withSimpleName(YarnPatcherPlugin.NAME + "_" + ((JavaType.GenericTypeVariable) id.getType()).getName());
            Object parent = getCursor().getParentTreeCursor().getValue();
            JavaType.Variable field = id.getFieldType();
            if (field != null && field.hasFlags(Flag.Static) && field.getOwner() instanceof JavaType.FullyQualified
                    && !binaryName(((JavaType.FullyQualified) field.getOwner())).matches(".*\\$[0-9].*")
                    && !(parent instanceof J.VariableDeclarations.NamedVariable && ((J.VariableDeclarations.NamedVariable) parent).getName() == id)
                    && !(parent instanceof J.FieldAccess && ((J.FieldAccess) parent).getName() == id)
                    && !(parent instanceof J.Assignment && ((J.Assignment) parent).getVariable() == id)
                    && !(parent instanceof J.EnumValue) && !(parent instanceof J.Case) && !(parent instanceof J.Import))
                return id.withSimpleName(mapped(((JavaType.FullyQualified) field.getOwner())) + "." + id.getSimpleName());
            if (id.getFieldType() == null && !(parent instanceof J.ClassDeclaration && ((J.ClassDeclaration) parent).getName() == id)) {
                String target = typeName(id.getType(), id.getSimpleName());
                if (target != null) return id.withSimpleName(target);
            }
            return super.visitIdentifier(id, ctx);
        }
        @Override public J visitTypeParameter(J.TypeParameter original, ExecutionContext ctx) {
            J.TypeParameter parameter = (J.TypeParameter) super.visitTypeParameter(original, ctx);
            if (original.getName() instanceof J.Identifier && targetSimpleNames.contains(((J.Identifier) original.getName()).getSimpleName())) {
                return parameter.withName(((J.Identifier) parameter.getName()).withSimpleName(YarnPatcherPlugin.NAME + "_" + ((J.Identifier) original.getName()).getSimpleName()));
            }
            return parameter;
        }
        @Override public J visitFieldAccess(J.FieldAccess field, ExecutionContext ctx) {
            if (field.getName().getFieldType() == null) {
                String target = typeName(field.getType(), field.getSimpleName());
                if (target != null) {
                    if (field.getTarget() instanceof J.ParameterizedType) {
                        J.FieldAccess visited = (J.FieldAccess) super.visitFieldAccess(field, ctx);
                        return visited.withName(visited.getName().withSimpleName(target.substring(target.lastIndexOf('.') + 1)));
                    }
                    return ((J) TypeTree.build(target)).withPrefix(field.getPrefix());
                }
            }
            return super.visitFieldAccess(field, ctx);
        }
        @Override public J visitClassDeclaration(J.ClassDeclaration original, ExecutionContext ctx) {
            J.ClassDeclaration c = (J.ClassDeclaration) super.visitClassDeclaration(original, ctx);
            if (original.getType() != null && classes.containsKey(original.getType().getFullyQualifiedName().replace('$', '.'))) {
                String target = mapped(original.getType());
                c = c.withName(c.getName().withSimpleName(target.substring(target.lastIndexOf('.') + 1)));
            }
            Object parent = getCursor().getParentTreeCursor().getValue();
            J.ClassDeclaration enclosing = getCursor().getParentTreeCursor().firstEnclosing(J.ClassDeclaration.class);
            if (parent instanceof J.CompilationUnit || enclosing != null && parent == enclosing.getBody())
                c = c.withModifiers(publicAccess(c.getModifiers()));
            return maybeAutoFormat(original, c, ctx);
        }
        @Override public J visitMethodDeclaration(J.MethodDeclaration original, ExecutionContext ctx) {
            J.MethodDeclaration m = (J.MethodDeclaration) super.visitMethodDeclaration(original, ctx);
            if (original.isConstructor() && original.getMethodType() != null) {
                String target = mapped(original.getMethodType().getDeclaringType());
                m = m.withName(m.getName().withSimpleName(target.substring(target.lastIndexOf('.') + 1)));
            }
            J.ClassDeclaration owner = getCursor().firstEnclosing(J.ClassDeclaration.class);
            if (owner != null && !(original.isConstructor() && owner.getKind() == J.ClassDeclaration.Kind.Type.Enum))
                m = m.withModifiers(publicAccess(m.getModifiers()));
            List<J.Modifier> modifiers = new ArrayList<>();
            for (J.Modifier modifier : m.getModifiers()) {
                modifiers.add(modifier.withPrefix(modifier.getPrefix().withWhitespace(modifiers.isEmpty() ? "" : " ")));
            }
            m = m.withModifiers(modifiers);
            if (m.getReturnTypeExpression() != null && !modifiers.isEmpty())
                m = m.withReturnTypeExpression(m.getReturnTypeExpression().withPrefix(m.getReturnTypeExpression().getPrefix().withWhitespace(" ")));
            return maybeAutoFormat(original, m, ctx);
        }
        @Override public J visitVariableDeclarations(J.VariableDeclarations original, ExecutionContext ctx) {
            J.VariableDeclarations vars = (J.VariableDeclarations) super.visitVariableDeclarations(original, ctx);
            J.ClassDeclaration owner = getCursor().firstEnclosing(J.ClassDeclaration.class);
            Object parent = getCursor().getParentTreeCursor().getValue();
            if (owner != null && parent == owner.getBody()) {
                vars = vars.withModifiers(publicAccess(vars.getModifiers()));
                List<J.Modifier> modifiers = new ArrayList<>();
                for (J.Modifier modifier : vars.getModifiers()) {
                    modifiers.add(modifier.withPrefix(modifier.getPrefix().withWhitespace(modifiers.isEmpty() ? "" : " ")));
                }
                vars = vars.withModifiers(modifiers);
                if (vars.getTypeExpression() != null && !modifiers.isEmpty())
                    vars = vars.withTypeExpression(vars.getTypeExpression().withPrefix(vars.getTypeExpression().getPrefix().withWhitespace(" ")));
            }
            return maybeAutoFormat(original, vars, ctx);
        }
        @Override public J visitNewClass(J.NewClass creation, ExecutionContext ctx) {
            J.NewClass n = (J.NewClass) super.visitNewClass(creation, ctx);
            TypeTree originalClass = creation.getClazz();
            boolean implicitInner = (originalClass instanceof J.Identifier || originalClass instanceof J.ParameterizedType && ((J.ParameterizedType) originalClass).getClazz() instanceof J.Identifier)
                                    && creation.getConstructorType() != null && creation.getConstructorType().getDeclaringType().getOwningClass() != null
                                    && !creation.getConstructorType().getDeclaringType().hasFlags(Flag.Static);
            if (n.getEnclosing() != null || implicitInner) {
                if (n.getClazz() instanceof J.ParameterizedType && ((J.ParameterizedType) n.getClazz()).getClazz() instanceof J.Identifier)
                    n = n.withClazz(((J.ParameterizedType) n.getClazz()).withClazz(((J.Identifier) ((J.ParameterizedType) n.getClazz()).getClazz()).withSimpleName(((J.Identifier) ((J.ParameterizedType) n.getClazz()).getClazz()).getSimpleName().substring(((J.Identifier) ((J.ParameterizedType) n.getClazz()).getClazz()).getSimpleName().lastIndexOf('.') + 1))));
                else if (n.getClazz() instanceof J.Identifier)
                    n = n.withClazz(((J.Identifier) n.getClazz()).withSimpleName(((J.Identifier) n.getClazz()).getSimpleName().substring(((J.Identifier) n.getClazz()).getSimpleName().lastIndexOf('.') + 1)));
                else if (n.getClazz() instanceof J.ParameterizedType && ((J.ParameterizedType) n.getClazz()).getClazz() instanceof J.FieldAccess)
                    n = n.withClazz(((J.ParameterizedType) n.getClazz()).withClazz(((J.FieldAccess) ((J.ParameterizedType) n.getClazz()).getClazz()).getName()));
                else if (n.getClazz() instanceof J.FieldAccess) n = n.withClazz(((J.FieldAccess) n.getClazz()).getName());
            }
            return n;
        }
    }
    static final class Members extends JavaIsoVisitor<ExecutionContext> {
        private final MappingIndex index;
        private final SourceCompatibility compatibility;
        private final Map<String, Set<String>> globalNames = new HashMap<>();
        private final Map<String, Boolean> overloads = new HashMap<>();
        private final JavaParser expressionParser = JavaParser.fromJavaVersion().build();
        private final Map<String, J.TypeCast> castTemplates = new HashMap<>();
        private final Set<String> methodOwners;
        private final Map<String, Boolean> mappedHierarchy = new HashMap<>();
        Members(MappingIndex index, Set<String> methodOwners) {
            this(index, methodOwners, null);
        }
        Members(MappingIndex index, Set<String> methodOwners, SourceCompatibility compatibility) {
            this.index = index;
            this.methodOwners = methodOwners;
            this.compatibility = compatibility;
            index.methods.forEach((key, value) -> globalNames.computeIfAbsent(key.name(), k -> new HashSet<>()).add(value));
        }

        @Override public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration original, ExecutionContext ctx) {
            J.ClassDeclaration result = super.visitClassDeclaration(original, ctx);
            if (compatibility != null && original.getType() != null) {
                List<Statement> statements = new ArrayList<>(result.getBody().getStatements());
                for (com.github.yarnpatcher.SourceCompatibility.Bridge bridge : compatibility.bridges.getOrDefault(binaryName(original.getType()), Jdk8Compat.list())) {
                    J.MethodDeclaration alias = visitMethodDeclaration(bridge.method(), ctx);
                    alias = alias.withId(Tree.randomId()).withName(alias.getName().withSimpleName(bridge.name()))
                            .withBody(bridge.body()).withLeadingAnnotations(Jdk8Compat.list());
                    statements.add(alias);
                }
                result = result.withBody(result.getBody().withStatements(statements));
            }
            return result;
        }

        @Override public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
            J.MethodDeclaration m = super.visitMethodDeclaration(method, ctx);
            String name = methodName(method.getMethodType());
            if (name == null) return m;
            return m.withName(m.getName().withSimpleName(name)).withMethodType(m.getMethodType().withName(name));
        }

        @Override public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
            J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
            String name = methodName(method.getMethodType());
            if (name == null) return m;
            m = m.withName(m.getName().withSimpleName(name)).withMethodType(m.getMethodType().withName(name));
            if (mergedOverload(method.getMethodType(), name)) {
                List<Expression> args = new ArrayList<>(m.getArguments());
                for (int i = 0; i < args.size() && i < method.getMethodType().getParameterTypes().size(); i++) {
                    JavaType expected = method.getMethodType().getParameterTypes().get(i);
                    if (args.get(i) instanceof J.Lambda || args.get(i) instanceof J.MemberReference) expected = method.getArguments().get(i).getType();
                    if (expected != null && !(expected instanceof JavaType.GenericTypeVariable) && !(args.get(i) instanceof J.Empty))
                        args.set(i, cast(args.get(i), expected));
                }
                m = m.withArguments(args);
            }
            return m;
        }

        private Expression cast(Expression expression, JavaType type) {
            String spelling = sourceType(type);
            J.TypeCast cast = castTemplates.computeIfAbsent(spelling, typeName -> {
                String source = "class Cast { Object cast() { return (" + typeName + ") null; } }";
                SourceFile parsed = expressionParser.reset().parse(source).findFirst().orElseThrow(() -> new IllegalStateException("Expected a nonempty result"));
                if (!(parsed instanceof J.CompilationUnit)) throw new IllegalStateException("Cannot parse generated cast: " + source + " / " + parsed.getMarkers().findFirst(ParseExceptionResult.class).map(ParseExceptionResult::getMessage).orElse("unknown error"));
                org.openrewrite.java.tree.J.MethodDeclaration method = (J.MethodDeclaration) ((J.CompilationUnit) parsed).getClasses().get(0).getBody().getStatements().get(0);
                return (J.TypeCast) ((J.Return) method.getBody().getStatements().get(0)).getExpression();
            });
            cast = (J.TypeCast) new JavaIsoVisitor<ExecutionContext>() {
                @Override public J preVisit(J tree, ExecutionContext context) {
                    return tree.withId(Tree.randomId());
                }
            } .visitNonNull(cast, new InMemoryExecutionContext());
            return cast.withExpression(expression.withPrefix(Space.SINGLE_SPACE)).withPrefix(expression.getPrefix());
        }

        private String sourceType(JavaType type) {
            if (type instanceof JavaType.Parameterized) return sourceType(((JavaType.Parameterized) type).getType()) + "<" + ((JavaType.Parameterized) type).getTypeParameters().stream().map(this::sourceType).collect(Collectors.joining(", ")) + ">";
            if (type instanceof JavaType.Array) return sourceType(((JavaType.Array) type).getElemType()) + "[]";
            if (type instanceof JavaType.GenericTypeVariable) {
                if (javax.lang.model.SourceVersion.isIdentifier(((JavaType.GenericTypeVariable) type).getName())) return ((JavaType.GenericTypeVariable) type).getName();
                if (((JavaType.GenericTypeVariable) type).getBounds().isEmpty()) return "?";
                JavaType bound = ((JavaType.GenericTypeVariable) type).getBounds().get(0);
                Set<JavaType> seen = Collections.newSetFromMap(new IdentityHashMap<>());
                while (bound instanceof JavaType.GenericTypeVariable && !javax.lang.model.SourceVersion.isIdentifier(((JavaType.GenericTypeVariable) bound).getName()) && !((JavaType.GenericTypeVariable) bound).getBounds().isEmpty() && seen.add(bound))
                    bound = ((JavaType.GenericTypeVariable) bound).getBounds().get(0);
                return "? " + (((JavaType.GenericTypeVariable) type).getVariance() == JavaType.GenericTypeVariable.Variance.CONTRAVARIANT ? "super " : "extends ") + sourceType(bound);
            }
            if (type instanceof JavaType.Primitive) return ((JavaType.Primitive) type).getKeyword();
            JavaType.FullyQualified fq = TypeUtils.asFullyQualified(type);
            if (fq != null) {
                if (binaryName(fq).matches(".*\\$[0-9].*")) {
                    if (!fq.getInterfaces().isEmpty()) return sourceType(fq.getInterfaces().get(0));
                    if (fq.getSupertype() != null) return sourceType(fq.getSupertype());
                }
                return index.map(binaryName(fq)).replace('/', '.').replace('$', '.');
            }
            throw new IllegalStateException("Cannot express source cast for " + type);
        }

        private boolean mergedOverload(JavaType.Method method, String mapped) {
            String key = binaryName(method.getDeclaringType()) + "." + mapped;
            return overloads.computeIfAbsent(key, k -> {
                List<JavaType.Method> methods = new ArrayList<>();
                declarations(method.getDeclaringType(), methods, new HashSet<>());
                Set<String> sourceNames = new HashSet<>();
                for (org.openrewrite.java.tree.JavaType.Method candidate : methods) if (mapped.equals(methodName(candidate))) sourceNames.add(candidate.getName());
                return sourceNames.size() > 1;
            });
        }

        @Override public J.MemberReference visitMemberReference(J.MemberReference reference, ExecutionContext ctx) {
            J.MemberReference m = super.visitMemberReference(reference, ctx);
            String name = methodName(reference.getMethodType());
            if (name == null) return m;
            return m.withReference(m.getReference().withSimpleName(name)).withMethodType(m.getMethodType().withName(name));
        }

        @Override public J.Identifier visitIdentifier(J.Identifier identifier, ExecutionContext ctx) {
            J.Identifier id = super.visitIdentifier(identifier, ctx);
            JavaType.Variable field = identifier.getFieldType();
            J.MethodDeclaration constructor = getCursor().firstEnclosing(J.MethodDeclaration.class);
            J.ClassDeclaration record = getCursor().firstEnclosing(J.ClassDeclaration.class);
            if (constructor != null && constructor.isConstructor() && record != null && record.getKind() == J.ClassDeclaration.Kind.Type.Record && record.getType() != null) {
                String component = index.fieldNames.getOrDefault(binaryName(record.getType()), Collections.emptyMap()).get(id.getSimpleName());
                if (component != null) return id.withSimpleName(component);
            }
            if (field == null) {
                if (getCursor().getParentTreeCursor().getValue() instanceof J.Case) {
                    JavaType.FullyQualified enumType = TypeUtils.asFullyQualified(identifier.getType());
                    if (enumType != null) {
                        String renamed = index.fields.get(new MappingIndex.Member(binaryName(enumType), id.getSimpleName(), descriptor(enumType)));
                        if (renamed != null) return id.withSimpleName(renamed);
                    }
                }
                return id;
            }
            if (!(field.getOwner() instanceof JavaType.FullyQualified)) return id;
            String name = lookup(((JavaType.FullyQualified) field.getOwner()), field.getName(), descriptor(field.getType()), index.fields, new HashSet<>());
            return name == null ? id : id.withSimpleName(name).withFieldType(field.withName(name));
        }

        @Override public J.FieldAccess visitFieldAccess(J.FieldAccess original, ExecutionContext ctx) {
            J.FieldAccess field = super.visitFieldAccess(original, ctx);
            JavaType.FullyQualified owner = TypeUtils.asFullyQualified(original.getTarget().getType());
            if (owner != null && original.getType() != null && !(original.getType() instanceof JavaType.Unknown)) {
                String name = index.fields.get(new MappingIndex.Member(binaryName(owner), original.getSimpleName(), descriptor(original.getType())));
                if (name != null) return field.withName(field.getName().withSimpleName(name));
            }
            return field;
        }

        @Override public J.EnumValue visitEnumValue(J.EnumValue original, ExecutionContext ctx) {
            J.EnumValue value = super.visitEnumValue(original, ctx);
            J.ClassDeclaration owner = getCursor().firstEnclosing(J.ClassDeclaration.class);
            if (owner == null || owner.getType() == null) return value;
            String name = index.fields.get(new MappingIndex.Member(binaryName(owner.getType()), original.getName().getSimpleName(), descriptor(owner.getType())));
            return name == null ? value : value.withName(value.getName().withSimpleName(name));
        }

        @Override public J.Import visitImport(J.Import original, ExecutionContext ctx) {
            J.Import imp = super.visitImport(original, ctx);
            if (!original.isStatic() || original.getQualid().getSimpleName().equals("*")) return imp;
            String owner = original.getTypeName().replace('.', '/');
            String old = original.getQualid().getSimpleName();
            Set<String> names = new HashSet<>();
            Stream.concat(index.methods.entrySet().stream(), index.fields.entrySet().stream())
                  .filter(e -> e.getKey().owner().equals(owner) && e.getKey().name().equals(old))
                  .forEach(e -> names.add(e.getValue()));
            JavaType.FullyQualified ownerType = TypeUtils.asFullyQualified(original.getQualid().getTarget().getType());
            if (ownerType != null) {
                for (JavaType.Method method : ownerType.getMethods()) {
                    if (method.getName().equals(old)) names.add(Optional.ofNullable(methodName(method)).orElse(old));
                }
            }
            if (names.size() > 1) throw new IllegalStateException("Static import has differently renamed overloads: " + owner + "." + old + "; use a wildcard import.");
            if (names.isEmpty()) return imp;
            return imp.withQualid(imp.getQualid().withName(imp.getQualid().getName().withSimpleName(names.iterator().next())));
        }

        String methodName(JavaType.Method method) {
            return methodName(method, new HashSet<>());
        }

        private String methodName(JavaType.Method method, Set<JavaType.Method> seen) {
            if (method == null || method.isConstructor()) return null;
            if (!hasMappedMethods(method.getDeclaringType(), new HashSet<>()) && !globalNames.containsKey(method.getName())) return null;
            if (!seen.add(method)) return null;
            StringBuilder desc = new StringBuilder("(");
            for (JavaType parameter : method.getParameterTypes()) desc.append(descriptor(parameter));
            desc.append(')').append(descriptor(method.getReturnType()));
            String name = lookup(method.getDeclaringType(), method.getName(), desc.toString(), index.methods, new HashSet<>());
            if (name != null) return name;
            // Invocation types may contain inferred generic arguments. Mappings use declaration erasure.
            Set<String> declaredNames = new HashSet<>();
            JavaType.FullyQualified declarationOwner = method.getDeclaringType();
            if (declarationOwner instanceof JavaType.Parameterized) declarationOwner = ((JavaType.Parameterized) declarationOwner).getType();
            List<JavaType.Method> declarations = new ArrayList<>();
            declarations(declarationOwner, declarations, new HashSet<>());
            for (JavaType.Method declared : declarations) {
                if (!declared.getName().equals(method.getName()) || declared.getParameterTypes().size() != method.getParameterTypes().size()) continue;
                boolean matches = true;
                StringBuilder declaredDesc = new StringBuilder("(");
                for (int i = 0; i < declared.getParameterTypes().size(); i++) {
                    JavaType parameter = declared.getParameterTypes().get(i);
                    String erased = descriptor(parameter);
                    if (!hasGeneric(parameter) && !erased.equals(descriptor(method.getParameterTypes().get(i)))) matches = false;
                    declaredDesc.append(erased);
                }
                if (!matches) continue;
                declaredDesc.append(')').append(descriptor(declared.getReturnType()));
                String mapped = index.methods.get(new MappingIndex.Member(binaryName(declared.getDeclaringType()), declared.getName(), declaredDesc.toString()));
                if (mapped != null) declaredNames.add(mapped);
            }
            if (declaredNames.size() > 1) throw new IllegalStateException("Ambiguous inferred generic method: " + method);
            if (!declaredNames.isEmpty()) return declaredNames.iterator().next();
            String inherited = TypeUtils.findOverriddenMethod(method).map(parent -> methodName(parent, seen)).orElse(null);
            if (inherited != null) return inherited;
            Set<String> global = globalNames.getOrDefault(method.getName(), Jdk8Compat.set());
            return SrgNames.method(method.getName()) && global.size() == 1 ? global.iterator().next() : null;
        }

        private boolean hasMappedMethods(JavaType.FullyQualified type, Set<String> seen) {
            if (type == null || !seen.add(type.getFullyQualifiedName())) return false;
            String name = type.getFullyQualifiedName();
            Boolean cached = mappedHierarchy.get(name);
            if (cached != null) return cached;
            boolean mapped = methodOwners.contains(binaryName(type)) || hasMappedMethods(type.getSupertype(), seen);
            if (!mapped) for (JavaType.FullyQualified parent : type.getInterfaces()) {
                    if (hasMappedMethods(parent, seen)) {
                        mapped = true;
                        break;
                    }
                }
            mappedHierarchy.put(name, mapped);
            return mapped;
        }

        private boolean hasGeneric(JavaType type) {
            return type instanceof JavaType.GenericTypeVariable || type instanceof JavaType.Array && hasGeneric(((JavaType.Array) type).getElemType());
        }

        private void declarations(JavaType.FullyQualified type, List<JavaType.Method> out, Set<String> seen) {
            if (type == null || !seen.add(type.getFullyQualifiedName())) return;
            if (type instanceof JavaType.Parameterized) type = ((JavaType.Parameterized) type).getType();
            out.addAll(type.getMethods());
            declarations(type.getSupertype(), out, seen);
            for (JavaType.FullyQualified parent : type.getInterfaces()) declarations(parent, out, seen);
        }

        private String lookup(JavaType.FullyQualified owner, String name, String desc,
                              Map<MappingIndex.Member, String> members, Set<String> seen) {
            if (owner == null || !seen.add(owner.getFullyQualifiedName())) return null;
            String mapped = members.get(new MappingIndex.Member(binaryName(owner), name, desc));
            if (mapped != null) return mapped;
            mapped = lookup(owner.getSupertype(), name, desc, members, seen);
            if (mapped != null) return mapped;
            for (JavaType.FullyQualified parent : owner.getInterfaces()) {
                mapped = lookup(parent, name, desc, members, seen);
                if (mapped != null) return mapped;
            }
            return null;
        }
    }

    static String descriptor(JavaType type) {
        if (type instanceof JavaType.Array) return "[" + descriptor(((JavaType.Array) type).getElemType());
        if (type instanceof JavaType.Primitive) {
            switch ((JavaType.Primitive) type) {
            case Boolean:
                return "Z";
            case Byte:
                return "B";
            case Char:
                return "C";
            case Double:
                return "D";
            case Float:
                return "F";
            case Int:
                return "I";
            case Long:
                return "J";
            case Short:
                return "S";
            case Void:
                return "V";
            case String:
                return "Ljava/lang/String;";
            default:
                throw new IllegalStateException("Unresolved primitive: " + type);
            }
        }
        if (type instanceof JavaType.GenericTypeVariable) {
            return ((JavaType.GenericTypeVariable) type).getBounds().isEmpty() ? "Ljava/lang/Object;" : descriptor(((JavaType.GenericTypeVariable) type).getBounds().get(0));
        }
        if (type instanceof JavaType.Intersection && !((JavaType.Intersection) type).getBounds().isEmpty()) {
            return descriptor(((JavaType.Intersection) type).getBounds().get(0));
        }
        JavaType.FullyQualified fq = TypeUtils.asFullyQualified(type);
        if (fq != null) return "L" + binaryName(fq) + ";";
        throw new IllegalStateException("Unresolved Java type; provide the complete Mojang compile classpath: " + type);
    }

    static String binaryName(JavaType.FullyQualified type) {
        String name = type.getFullyQualifiedName();
        if (name.contains("$")) return name.replace('.', '/');
        JavaType.FullyQualified owner = type.getOwningClass();
        if (owner != null) return binaryName(owner) + "$" + name.substring(name.lastIndexOf('.') + 1);
        return name.replace('.', '/');
    }
}
