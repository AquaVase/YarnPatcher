package com.github.yarnpatcher;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.*;
import org.gradle.work.DisableCachingByDefault;
import java.io.IOException;

@DisableCachingByDefault(because = "Explicit source replacement must back up the current workspace on every invocation")
public abstract class InstallSourcesTask extends DefaultTask {
    public InstallSourcesTask() {
        getOutputs().upToDateWhen(task -> false);
    }
    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE) public abstract DirectoryProperty getStagedDirectory();
    @Internal public abstract DirectoryProperty getDestinationDirectory();
    @Internal public abstract DirectoryProperty getBackupRoot();

    @TaskAction public void install() throws IOException {
        java.nio.file.Path backup = SourceInstaller.install(getStagedDirectory().get().getAsFile().toPath(),
            getDestinationDirectory().get().getAsFile().toPath(), getBackupRoot().get().getAsFile().toPath());
        getLogger().lifecycle("Installed Yarn sources into {}; original backup: {}", getDestinationDirectory().get(), backup);
    }
}
