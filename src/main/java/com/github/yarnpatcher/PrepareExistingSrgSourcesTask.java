package com.github.yarnpatcher;

import java.util.stream.Collectors;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.*;
import org.gradle.api.tasks.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.zip.ZipFile;

/** Srg2Source leaves explicit static imports unchanged; repair them before reparsing. */
@CacheableTask
public abstract class PrepareExistingSrgSourcesTask extends DefaultTask {
    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE) public abstract DirectoryProperty getOriginalSources();
    @InputFile @PathSensitive(PathSensitivity.NONE) public abstract RegularFileProperty getSourceArchive();
    @InputFile @PathSensitive(PathSensitivity.NONE) public abstract RegularFileProperty getNamedToSrg();
    @Classpath public abstract ConfigurableFileCollection getClasspath();
    @OutputDirectory public abstract DirectoryProperty getOutputDirectory();
    @Input public String getRuntimeJavaVersion() {
        return System.getProperty("java.version");
    }

    @TaskAction public void prepare() throws IOException {
        Path original = getOriginalSources().get().getAsFile().toPath();
        Path output = getOutputDirectory().get().getAsFile().toPath().toAbsolutePath().normalize();
        SourceInstaller.requireDisjoint(original, output);
        SourceInstaller.deleteTree(output);
        Files.createDirectories(output);
        try (java.util.zip.ZipFile zip = new ZipFile(getSourceArchive().get().getAsFile())) {
            for (java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries(); entries.hasMoreElements();) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                Path target = output.resolve(entry.getName()).normalize();
                if (!target.startsWith(output)) throw new IOException("Unsafe archive entry: " + entry.getName());
                if (entry.isDirectory()) Files.createDirectories(target);
                else {
                    Files.createDirectories(target.getParent());
                    try (java.io.InputStream stream = zip.getInputStream(entry)) {
                        Files.copy(stream, target);
                    }
                }
            }
        }
        ExistingStaticImports.repair(original, output, getNamedToSrg().get().getAsFile().toPath(),
                                     getClasspath().getFiles().stream().map(java.io.File::toPath).collect(Collectors.toList()));
        // Range application only promises Java output; retain other files from the source tree.
        try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(original)) {
            for (Path path : paths.filter(Files::isRegularFile).filter(p -> !p.toString().endsWith(".java")).collect(Collectors.toList())) {
                Path target = output.resolve(original.relativize(path));
                if (!Files.exists(target)) {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target);
                }
            }
        }
    }
}
