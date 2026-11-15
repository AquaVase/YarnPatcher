package com.github.yarnpatcher;

import java.util.stream.Collectors;

import net.minecraftforge.gradle.common.config.MCPConfigV2;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipFile;

/** TSRG v1 omits parameter metadata; MCPConfig supplies static methods and constructor IDs. */
final class LegacyMcpParameters {
    static final class Constructor {
        private final String id;
        private final String descriptor;
        Constructor(String id, String descriptor) {
            this.id = id;
            this.descriptor = descriptor;
        }
        String id() {
            return id;
        }
        String descriptor() {
            return descriptor;
        }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Constructor)) return false;
            Constructor that = (Constructor) other;
            return java.util.Objects.equals(id, that.id) && java.util.Objects.equals(descriptor, that.descriptor);
        }
        @Override public int hashCode() {
            return java.util.Objects.hash(id, descriptor);
        }
    }
    final Set<String> statics = new HashSet<>();
    final Map<String, List<Constructor>> constructors = new HashMap<>();
    static LegacyMcpParameters read(Path archive) throws IOException {
        com.github.yarnpatcher.LegacyMcpParameters result = new LegacyMcpParameters();
        if (archive == null) return result;
        net.minecraftforge.gradle.common.config.MCPConfigV2 config = MCPConfigV2.getFromArchive(archive.toFile());
        try (java.util.zip.ZipFile zip = new ZipFile(archive.toFile())) {
            if (config.getData("statics") != null) result.statics.addAll(Jdk8Compat.lines(read(zip, config.getData("statics"))).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList()));
            if (config.getData("constructors") != null) for (String line : Jdk8Compat.lines(read(zip, config.getData("constructors"))).collect(Collectors.toList())) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length == 3) result.constructors.computeIfAbsent(parts[1], k -> new ArrayList<>()).add(new Constructor(parts[0], parts[2]));
                }
        }
        return result;
    }
    private static String read(ZipFile zip, String name) throws IOException {
        java.util.zip.ZipEntry entry = zip.getEntry(name);
        if (entry == null) throw new IOException("Missing MCPConfig metadata: " + name);
        try (java.io.InputStream input = zip.getInputStream(entry)) {
            return new String(Jdk8Compat.readAllBytes(input), StandardCharsets.UTF_8);
        }
    }
}
