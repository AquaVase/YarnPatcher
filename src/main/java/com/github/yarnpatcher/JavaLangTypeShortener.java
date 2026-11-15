package com.github.yarnpatcher;

import java.util.stream.Collectors;

import org.openrewrite.*;
import org.openrewrite.java.*;
import org.openrewrite.java.tree.*;
import java.util.*;

/** java.lang is implicitly imported, so shortening it must also account for package and inherited names. */
final class JavaLangTypeShortener {
    static J.CompilationUnit shorten(J.CompilationUnit cu, Set<String> unavailable, ExecutionContext ctx) {
        Set<String> blocked = new HashSet<>(unavailable);
        boolean[] wildcard = {false};
        for (org.openrewrite.java.tree.J.Import imp : cu.getImports()) {
            String name = imp.getQualid().getSimpleName();
            if (name.equals("*")) wildcard[0] = true;
            else if (imp.isStatic() || !imp.getTypeName().equals("java.lang." + name)) blocked.add(name);
        }
        new JavaIsoVisitor<ExecutionContext>() {
            @Override public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration declaration, ExecutionContext context) {
                blocked.add(declaration.getSimpleName());
                fieldNames(declaration.getType(), blocked, new HashSet<>());
                return super.visitClassDeclaration(declaration, context);
            }
            @Override public J.TypeParameter visitTypeParameter(J.TypeParameter parameter, ExecutionContext context) {
                if (parameter.getName() instanceof J.Identifier) blocked.add(((J.Identifier) parameter.getName()).getSimpleName());
                return super.visitTypeParameter(parameter, context);
            }
            @Override public J.VariableDeclarations.NamedVariable visitVariable(J.VariableDeclarations.NamedVariable variable, ExecutionContext context) {
                blocked.add(variable.getSimpleName());
                return super.visitVariable(variable, context);
            }
            @Override public J.EnumValue visitEnumValue(J.EnumValue value, ExecutionContext context) {
                blocked.add(value.getName().getSimpleName());
                return super.visitEnumValue(value, context);
            }
        } .visit(cu, ctx);
        if (wildcard[0]) return cu;
        cu = (J.CompilationUnit) new JavaVisitor<ExecutionContext>() {
            @Override public J visitImport(J.Import imp, ExecutionContext context) {
                return imp;
            }
            @Override public J visitFieldAccess(J.FieldAccess original, ExecutionContext context) {
                J.FieldAccess access = (J.FieldAccess) super.visitFieldAccess(original, context);
                if (original.getName().getFieldType() == null && !blocked.contains(original.getSimpleName())
                        && isJavaLangType(original)
                        && original.getTarget() instanceof J.FieldAccess && ((J.FieldAccess) original.getTarget()).getSimpleName().equals("lang")
                        && ((J.FieldAccess) original.getTarget()).getTarget() instanceof J.Identifier && ((J.Identifier) ((J.FieldAccess) original.getTarget()).getTarget()).getSimpleName().equals("java") && ((J.Identifier) ((J.FieldAccess) original.getTarget()).getTarget()).getFieldType() == null)
                    return access.getName().withPrefix(access.getPrefix());
                return access;
            }
        } .visitNonNull(cu, ctx);
        // Remove redundant single-type java.lang imports only where the implicit import resolves identically.
        return cu.withImports(cu.getImports().stream().filter(imp -> imp.isStatic()
                || !imp.getTypeName().equals("java.lang." + imp.getQualid().getSimpleName())
                || blocked.contains(imp.getQualid().getSimpleName())).collect(Collectors.toList()));
    }

    private static boolean isJavaLangType(J.FieldAccess access) {
        return access.getType() instanceof JavaType.FullyQualified && ((JavaType.FullyQualified) access.getType()).getFullyQualifiedName().equals("java.lang." + access.getSimpleName())
               || access.getType() == JavaType.Primitive.String && access.getSimpleName().equals("String");
    }

    private static void fieldNames(JavaType.FullyQualified type, Set<String> names, Set<String> visited) {
        if (type == null || !visited.add(type.getFullyQualifiedName())) return;
        type.getMembers().forEach(field -> names.add(field.getName()));
        fieldNames(type.getSupertype(), names, visited);
        for (org.openrewrite.java.tree.JavaType.FullyQualified parent : type.getInterfaces()) fieldNames(parent, names, visited);
    }
}
