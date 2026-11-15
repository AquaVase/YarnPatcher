package com.github.yarnpatcher;

import javax.tools.*;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/** Small compatibility helpers shared by the Java 8, 17 and 21 runtimes. */
final class Jdk8Compat {
    private Jdk8Compat() { }

    private static boolean compilerInitialized;

    @SafeVarargs static <T> List<T> list(T... values) {
        return Collections.unmodifiableList(Arrays.asList(values.clone()));
    }

    @SafeVarargs static <T> Set<T> set(T... values) {
        return Collections.unmodifiableSet(new LinkedHashSet<T>(Arrays.asList(values)));
    }

    static String readString(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    static Path writeString(Path path, CharSequence text) throws IOException {
        return writeString(path, text, StandardCharsets.UTF_8);
    }

    static Path writeString(Path path, CharSequence text, Charset charset) throws IOException {
        return Files.write(path, text.toString().getBytes(charset));
    }

    static byte[] readAllBytes(InputStream input) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int count; (count = input.read(buffer)) >= 0;) result.write(buffer, 0, count);
        return result.toByteArray();
    }

    static Stream<String> lines(String text) {
        return new BufferedReader(new StringReader(text)).lines();
    }

    static Iterable<? extends JavaFileObject> sourceFiles(StandardJavaFileManager manager, Collection<Path> paths) {
        return manager.getJavaFileObjectsFromFiles(paths.stream().map(Path::toFile).collect(Collectors.toList()));
    }

    static synchronized JavaCompiler compiler() {
        // Java 8 keeps the public compiler-tree API in tools.jar, outside the JRE.
        if (!compilerInitialized && System.getProperty("java.specification.version").equals("1.8")) {
            File tools = new File(System.getProperty("java.home"), "../lib/tools.jar");
            if (!tools.isFile()) tools = new File(System.getProperty("java.home"), "lib/tools.jar");
            if (!tools.isFile()) throw new IllegalStateException("Run Gradle with a full JDK 8, not a JRE: " + System.getProperty("java.home"));
            try {
                java.lang.reflect.Method add = java.net.URLClassLoader.class.getDeclaredMethod("addURL", java.net.URL.class);
                add.setAccessible(true);
                java.net.URL url = tools.toURI().toURL();
                ClassLoader system = ClassLoader.getSystemClassLoader();
                if (system instanceof java.net.URLClassLoader) add.invoke(system, url);
                ClassLoader plugin = Jdk8Compat.class.getClassLoader();
                if (plugin != system && plugin instanceof java.net.URLClassLoader) add.invoke(plugin, url);
            } catch (ReflectiveOperationException | IOException e) {
                throw new IllegalStateException("Cannot load the compiler from the current JDK 8", e);
            }
        }
        compilerInitialized = true;
        return ToolProvider.getSystemJavaCompiler();
    }
}
