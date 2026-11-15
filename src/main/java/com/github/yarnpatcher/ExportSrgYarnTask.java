package com.github.yarnpatcher;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.*;
import org.gradle.api.tasks.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Exports the MCP CSV dialect used by Minecraft Deobfuscator3000. */
@CacheableTask
public abstract class ExportSrgYarnTask extends DefaultTask {
    @InputFile @PathSensitive(PathSensitivity.NONE) public abstract RegularFileProperty getMappingsFile();
    @OutputDirectory public abstract DirectoryProperty getOutputDirectory();

    @TaskAction public void export() throws IOException {
        export(getMappingsFile().get().getAsFile().toPath(), getOutputDirectory().get().getAsFile().toPath());
    }

    static void export(Path input, Path output) throws IOException {
        Map<String, Map<String, Integer>> fields = new TreeMap<>(), methods = new TreeMap<>(), parameters = new TreeMap<>();
        for (String line : Files.readAllLines(input)) {
            String[] parts = line.split("\t", -1);
            if (parts.length >= 5 && parts[0].isEmpty() && (parts[1].equals("m") || parts[1].equals("f"))) {
                if (parts[1].equals("m") ? SrgNames.method(parts[3]) : SrgNames.field(parts[3])) add(parts[1].equals("m") ? methods : fields, parts[3], parts[4]);
            } else if (parts.length >= 6 && parts[0].isEmpty() && parts[1].isEmpty() && parts[2].equals("p") && SrgNames.parameter(parts[4])) {
                add(parameters, parts[4], parts[5]);
            }
        }
        Files.createDirectories(output);
        StringBuilder conflicts = new StringBuilder("source,selected,candidates\n");
        csv(output.resolve("fields.csv"), fields, conflicts);
        csv(output.resolve("methods.csv"), methods, conflicts);
        csv(output.resolve("params.csv"), parameters, conflicts);
        Path report = output.resolve("conflicts.csv");
        // Avoid reopening an unchanged report for writing while it is being viewed on Windows.
        if (!Files.exists(report) || !Jdk8Compat.readString(report).contentEquals(conflicts)) Jdk8Compat.writeString(report, conflicts);
    }

    private static void add(Map<String, Map<String, Integer>> mappings, String from, String to) {
        if (!javax.lang.model.SourceVersion.isIdentifier(to) || javax.lang.model.SourceVersion.isKeyword(to))
            throw new IllegalArgumentException("Invalid Yarn Java identifier: " + to);
        mappings.computeIfAbsent(from, k -> new TreeMap<>()).merge(to, 1, Integer::sum);
    }

    private static void csv(Path output, Map<String, Map<String, Integer>> mappings, StringBuilder conflicts) throws IOException {
        StringBuilder result = new StringBuilder("searge,name,side,desc\n");
        mappings.forEach((from, candidates) -> {
            // SRG names identify override families globally. CSV cannot assign two names to one family.
            String selected = candidates.keySet().stream().sorted(Comparator
                .comparing((String n) -> n.matches("(method|field)_[0-9]+"))
                .thenComparing(Comparator.comparingInt((String n) -> candidates.get(n)).reversed())
                .thenComparing(Comparator.naturalOrder())).findFirst().orElseThrow(() -> new IllegalStateException("Expected a nonempty result"));
            result.append(from).append(',').append(selected).append(",2,\n");
            if (candidates.size() > 1) conflicts.append(from).append(',').append(selected).append(',').append(String.join("|", candidates.keySet())).append('\n');
        });
        Jdk8Compat.writeString(output, result);
    }
}
