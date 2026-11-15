package com.github.yarnpatcher;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.*;
import java.io.IOException;
import java.nio.file.*;

/** Later passes address owners and descriptors in the already-renamed class namespace. */
@CacheableTask
public abstract class StageMappingsTask extends DefaultTask {
    @InputFile @PathSensitive(PathSensitivity.NONE) public abstract RegularFileProperty getMappingsFile();
    @Input public abstract Property<String> getStage();
    @OutputFile public abstract RegularFileProperty getOutputFile();

    @TaskAction public void prepare() throws IOException {
        write(getMappingsFile().get().getAsFile().toPath(), getOutputFile().get().getAsFile().toPath(), getStage().get());
    }

    static void write(Path input, Path output, String stage) throws IOException {
        MappingIndex index = MappingIndex.read(input, "srg", "yarn");
        if (!Jdk8Compat.set("classes", "fields", "methods").contains(stage)) throw new IllegalArgumentException("Unknown mapping stage: " + stage);
        boolean classes = stage.equals("classes");
        java.util.Map<com.github.yarnpatcher.MappingIndex.Member,java.lang.String> members = stage.equals("fields") ? index.fields : index.methods;
        StringBuilder tiny = new StringBuilder("tiny\t2\t0\tsrg\tyarn\n");
        index.classes.forEach((owner, target) -> {
            tiny.append("c\t").append(classes ? owner : target).append('\t').append(target).append('\n');
            if (classes) return;
            members.forEach((member, name) -> {
                if (!member.owner().equals(owner)) return;
                tiny.append(stage.equals("fields") ? "\tf\t" : "\tm\t")
                    .append(stage.equals("fields") ? index.mapDesc(member.descriptor()) : index.mapMethodDesc(member.descriptor()))
                    .append('\t').append(member.name()).append('\t').append(name).append('\n');
            });
        });
        Files.createDirectories(output.toAbsolutePath().getParent());
        Jdk8Compat.writeString(output, tiny);
    }
}
