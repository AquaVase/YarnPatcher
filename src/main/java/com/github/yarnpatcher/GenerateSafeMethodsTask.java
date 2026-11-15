package com.github.yarnpatcher;

import java.util.stream.Collectors;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.*;
import org.gradle.api.tasks.*;
import java.io.IOException;

@CacheableTask
public abstract class GenerateSafeMethodsTask extends DefaultTask {
    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE) public abstract DirectoryProperty getSourceDirectory();
    @InputFile @PathSensitive(PathSensitivity.NONE) public abstract RegularFileProperty getMethodsCsv();
    @Classpath public abstract ConfigurableFileCollection getClasspath();
    @OutputDirectory public abstract DirectoryProperty getOutputDirectory();
    @Input public String getRuntimeJavaVersion() {
        return System.getProperty("java.version");
    }

    @TaskAction public void generate() throws IOException {
        MethodConflictNames.generate(getSourceDirectory().get().getAsFile().toPath(), getMethodsCsv().get().getAsFile().toPath(),
        getOutputDirectory().get().getAsFile().toPath(), getClasspath().getFiles().stream().map(java.io.File::toPath).collect(Collectors.toList()),
        message -> getLogger().lifecycle(message));
    }
}
