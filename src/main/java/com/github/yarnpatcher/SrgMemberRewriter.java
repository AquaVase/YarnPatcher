package com.github.yarnpatcher;

import java.util.stream.Collectors;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.function.BiFunction;

/** Built-in CSV source remapping, restricted to complete Java identifiers. */
final class SrgMemberRewriter {
    static Map<String, String> readCsv(Path file) throws IOException {
        Map<String, String> result = new HashMap<>();
        for (String line : Files.readAllLines(file)) {
            String[] parts = line.split(",", -1);
            if (parts.length >= 2 && SrgNames.member(parts[0])) result.put(parts[0], parts[1]);
        }
        return result;
    }

    static int rewrite(Path input, Path output, Map<String, String> mappings) throws IOException {
        SourceInstaller.requireDisjoint(input, output);
        SourceInstaller.deleteTree(output);
        List<Path> files;
        try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(input)) {
            files = paths.filter(Files::isRegularFile).sorted().collect(Collectors.toList());
        }
        Map<Path, Map<String, String>> parameters = mappings.keySet().stream().anyMatch(SrgNames::parameter)
            ? ParameterNames.plan(files.stream().filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList()), mappings) : Collections.emptyMap();
        int count = 0;
        for (Path file : files) {
        Path target = output.resolve(input.relativize(file));
            Files.createDirectories(target.getParent());
            if (file.toString().endsWith(".java")) {
                String source = Jdk8Compat.readString(file);
                Map<String, String> safeNames = new HashMap<>(mappings);
                safeNames.putAll(parameters.getOrDefault(file.toAbsolutePath().normalize(), Collections.emptyMap()));
                Jdk8Compat.writeString(target, replace(source, identifier -> safeNames.getOrDefault(identifier, identifier)));
                count++;
            } else Files.copy(file, target);
        }
        return count;
    }

    static String replace(String text, UnaryOperator<String> identifiers) {
        return replace(text, (token, offset) -> identifiers.apply(token));
    }

    static String replace(String text, BiFunction<String, Integer, String> identifiers) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            int start = i;
            char c = text.charAt(i);
            if (text.startsWith("//", i)) {
                int end = text.indexOf('\n', i + 2);
                i = end < 0 ? text.length() : end;
            } else if (text.startsWith("/*", i)) {
                int end = text.indexOf("*/", i + 2);
                i = end < 0 ? text.length() : end + 2;
            } else if (text.startsWith("\"\"\"", i)) {
                i += 3;
                while (i < text.length()) {
                    if (text.charAt(i) == '\\') i = Math.min(i + 2, text.length());
                    else if (text.startsWith("\"\"\"", i)) {
                        i += 3;
                        break;
                    } else i++;
                }
            } else if (c == '\"' || c == '\'') {
                i++;
                while (i < text.length()) {
                    char next = text.charAt(i++);
                    if (next == '\\') i = Math.min(i + 1, text.length());
                    else if (next == c) break;
                }
            } else if (Character.isJavaIdentifierStart(text.codePointAt(i))) {
                i += Character.charCount(text.codePointAt(i));
                while (i < text.length() && Character.isJavaIdentifierPart(text.codePointAt(i))) i += Character.charCount(text.codePointAt(i));
                out.append(identifiers.apply(text.substring(start, i), start));
                continue;
            } else i++;
            out.append(text, start, i);
        }
        return out.toString();
    }
}
