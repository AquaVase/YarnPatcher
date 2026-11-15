package com.github.yarnpatcher;

import java.util.stream.Collectors;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.*;
import org.gradle.api.tasks.*;
import java.io.IOException;
import java.util.*;

@CacheableTask
public abstract class RemapSrgMembersTask extends DefaultTask {
    @Input public String getRuntimeJavaVersion() {
        return System.getProperty("java.version");
    }
    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE) public abstract DirectoryProperty getSourceDirectory();
    @InputFiles @PathSensitive(PathSensitivity.NONE) public abstract ConfigurableFileCollection getCsvFiles();
    @OutputDirectory public abstract DirectoryProperty getOutputDirectory();
    @Input public abstract org.gradle.api.provider.Property<Boolean> getOrganizeImports();
    @Classpath public abstract ConfigurableFileCollection getClasspath();
    public RemapSrgMembersTask() {
        getOrganizeImports().convention(false);
    }

    @TaskAction public void remap() throws IOException {
        Map<String, String> names = new HashMap<>();
        for (java.io.File csv : getCsvFiles().getFiles()) names.putAll(SrgMemberRewriter.readCsv(csv.toPath()));
        java.nio.file.Path output = getOutputDirectory().get().getAsFile().toPath();
        java.nio.file.Path csvOutput = getOrganizeImports().get() ? getTemporaryDir().toPath().resolve("parameters") : output;
        int count = SrgMemberRewriter.rewrite(getSourceDirectory().get().getAsFile().toPath(), csvOutput, names);
        if (getOrganizeImports().get()) YarnImportOrganizer.organize(csvOutput, output, getClasspath().getFiles().stream().map(java.io.File::toPath).collect(Collectors.toList()), message -> getLogger().lifecycle(message));
        getLogger().lifecycle("Applied {} SRG member/parameter mappings to {} Java files", names.size(), count);
    }
}
