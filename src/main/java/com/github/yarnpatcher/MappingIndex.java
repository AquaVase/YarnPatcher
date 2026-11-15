package com.github.yarnpatcher;

import net.fabricmc.tinyremapper.IMappingProvider;
import net.fabricmc.tinyremapper.TinyUtils;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import org.objectweb.asm.commons.Remapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipFile;

final class MappingIndex extends Remapper implements IMappingProvider.MappingAcceptor {
    static final class Member {
        private final String owner;
        private final String name;
        private final String descriptor;
        Member(String owner, String name, String descriptor) {
            this.owner = owner;
            this.name = name;
            this.descriptor = descriptor;
        }
        String owner() {
            return owner;
        }
        String name() {
            return name;
        }
        String descriptor() {
            return descriptor;
        }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Member)) return false;
            Member that = (Member) other;
            return java.util.Objects.equals(owner, that.owner) && java.util.Objects.equals(name, that.name) && java.util.Objects.equals(descriptor, that.descriptor);
        }
        @Override public int hashCode() {
            return java.util.Objects.hash(owner, name, descriptor);
        }
    }
    static final class Argument {
        private final Member method;
        private final int slot;
        Argument(Member method, int slot) {
            this.method = method;
            this.slot = slot;
        }
        Member method() {
            return method;
        }
        int slot() {
            return slot;
        }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Argument)) return false;
            Argument that = (Argument) other;
            return java.util.Objects.equals(method, that.method) && java.util.Objects.equals(slot, that.slot);
        }
        @Override public int hashCode() {
            return java.util.Objects.hash(method, slot);
        }
    }
    final Map<Argument, String> arguments = new LinkedHashMap<>();
    final Map<String, String> classes = new LinkedHashMap<>();
    final Map<Member, String> methods = new LinkedHashMap<>();
    final Map<Member, String> fields = new LinkedHashMap<>();
    final Map<String, Map<String, String>> fieldNames = new HashMap<>();
    final Map<String, Set<String>> fieldDescriptors = new HashMap<>();

    static MappingIndex read(Path path, String from, String to) {
        MappingIndex index = new MappingIndex();
        TinyUtils.createTinyMappingProvider(path, from, to).load(index);
        try {
            MemoryMappingTree tree = new MemoryMappingTree();
            MappingReader.read(path, tree);
            for (net.fabricmc.mappingio.tree.MappingTree.ClassMapping cls : tree.getClasses()) {
                String owner = cls.getName(from), target = cls.getName(to);
                if (owner == null) continue;
                index.classes.putIfAbsent(owner, target == null ? owner : target);
                for (net.fabricmc.mappingio.tree.MappingTree.MethodMapping method : cls.getMethods()) {
                    String name = method.getName(from), desc = method.getDesc(from);
                    if (name != null && desc != null) index.methods.putIfAbsent(new Member(owner, name, desc), method.getName(to) == null ? name : method.getName(to));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return index;
    }

    static MappingIndex archive(Path path, String from, String to) throws IOException {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            java.util.zip.ZipEntry entry = zip.getEntry("mappings/mappings.tiny");
            if (entry == null) throw new IOException("Missing mappings/mappings.tiny in " + path);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8))) {
                MappingIndex index = new MappingIndex();
                MemoryMappingTree tree = new MemoryMappingTree();
                MappingReader.read(reader, tree);
                TinyUtils.createMappingProvider(tree, from, to).load(index);
                // Tiny Remapper omits identity entries; retain their descriptors for TSRG fields.
                for (net.fabricmc.mappingio.tree.MappingTree.ClassMapping cls : tree.getClasses()) {
                    String owner = cls.getName(from);
                    String target = cls.getName(to);
                    if (owner == null) continue;
                    index.classes.putIfAbsent(owner, target == null ? owner : target);
                    for (net.fabricmc.mappingio.tree.MappingTree.FieldMapping field : cls.getFields()) {
                        String name = field.getName(from);
                        String desc = field.getDesc(from);
                        if (name == null || desc == null) continue;
                        index.fieldDescriptors.computeIfAbsent(owner + "." + name, k -> new LinkedHashSet<>()).add(desc);
                        index.fields.putIfAbsent(new Member(owner, name, desc), field.getName(to) == null ? name : field.getName(to));
                    }
                    for (net.fabricmc.mappingio.tree.MappingTree.MethodMapping method : cls.getMethods()) {
                        String name = method.getName(from);
                        String desc = method.getDesc(from);
                        if (name != null && desc != null) index.methods.putIfAbsent(new Member(owner, name, desc), method.getName(to) == null ? name : method.getName(to));
                    }
                }
                return index;
            }
        }
    }

    @Override public String map(String name) {
        return classes.getOrDefault(name, name);
    }
    @Override public String mapMethodName(String owner, String name, String desc) {
        return methods.getOrDefault(new Member(owner, name, desc), name);
    }
    @Override public String mapFieldName(String owner, String name, String desc) {
        return fields.getOrDefault(new Member(owner, name, desc), name);
    }
    @Override public void acceptClass(String from, String to) {
        classes.put(from, to);
    }
    @Override public void acceptMethod(IMappingProvider.Member m, String to) {
        methods.put(new Member(m.owner, m.name, m.desc), to);
    }
    @Override public void acceptField(IMappingProvider.Member m, String to) {
        fields.put(new Member(m.owner, m.name, m.desc), to);
        fieldNames.computeIfAbsent(m.owner, owner -> new HashMap<>()).put(m.name, to);
    }
    @Override public void acceptMethodArg(IMappingProvider.Member m, int slot, String name) {
        arguments.put(new Argument(new Member(m.owner, m.name, m.desc), slot), name);
    }
    @Override public void acceptMethodVar(IMappingProvider.Member m, int slot, int start, int index, String name) { }
}
