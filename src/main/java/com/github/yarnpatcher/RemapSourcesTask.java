package com.github.yarnpatcher;

import java.util.stream.Collectors;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.*;
import org.gradle.api.tasks.*;
import java.io.IOException;

@CacheableTask
public abstract class RemapSourcesTask extends DefaultTask {
    @Input public String getRuntimeJavaVersion() {
        return System.getProperty("java.version");
    }
    @InputFile @Optional @PathSensitive(PathSensitivity.NONE) public abstract RegularFileProperty getSourceArchive();
    @InputDirectory @Optional @PathSensitive(PathSensitivity.RELATIVE) public abstract DirectoryProperty getSourceDirectory();
    @InputFile @PathSensitive(PathSensitivity.NONE) public abstract RegularFileProperty getMappingsFile();
    @Input public abstract org.gradle.api.provider.Property<Boolean> getRewriteTypes();
    public RemapSourcesTask() {
        getRewriteTypes().convention(true);
    }
    @Classpath public abstract ConfigurableFileCollection getClasspath();
    @OutputDirectory public abstract DirectoryProperty getOutputDirectory();

    @TaskAction public void remap() throws IOException {
        java.nio.file.Path input = getTemporaryDir().toPath().resolve("sources").toAbsolutePath().normalize();
        if (getSourceDirectory().isPresent() == getSourceArchive().isPresent()) throw new IOException("Specify exactly one source archive or directory");
        if (getSourceDirectory().isPresent()) input = getSourceDirectory().get().getAsFile().toPath().toAbsolutePath().normalize();
        else {
            SourceInstaller.deleteTree(input);
            java.nio.file.Files.createDirectories(input);
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(getSourceArchive().get().getAsFile())) {
                for (java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries(); entries.hasMoreElements();) {
                    java.util.zip.ZipEntry entry = entries.nextElement();
                    java.nio.file.Path target = input.resolve(entry.getName()).normalize();
                    if (!target.startsWith(input)) throw new IOException("Unsafe source archive entry: " + entry.getName());
                    if (entry.isDirectory()) java.nio.file.Files.createDirectories(target);
                    else {
                        java.nio.file.Files.createDirectories(target.getParent());
                        try (java.io.InputStream stream = zip.getInputStream(entry)) {
                            java.nio.file.Files.copy(stream, target);
                        }
                    }
                }
            }
        }
        java.nio.file.Path output = getOutputDirectory().get().getAsFile().toPath().toAbsolutePath().normalize();
        SourceInstaller.requireDisjoint(input, output);
        SourceInstaller.deleteTree(output);
        int count = YarnSourceRewriter.rewrite(input, output, getMappingsFile().get().getAsFile().toPath(),
                                               getClasspath().getFiles().stream().map(java.io.File::toPath).collect(Collectors.toList()), message -> getLogger().lifecycle(message), getRewriteTypes().get());
        getLogger().lifecycle("Rewrote {} Java files to {}", count, output);
    }
}
