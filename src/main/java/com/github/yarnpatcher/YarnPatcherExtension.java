package com.github.yarnpatcher;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;

public abstract class YarnPatcherExtension {
    public abstract Property<String> getMinecraftVersion();
    public abstract Property<String> getYarnVersion();
    public abstract DirectoryProperty getSourceDirectory();
}
