# YarnPatcher

A Gradle plugin that converts Minecraft source code from an MCP-Reborn / ForgeGradle workspace to Yarn naming. ForgeGradle 6 is a hard dependency; use the Gradle 8 Wrapper, support JDK 8, 17, and 21, and select the Gradle JVM according to the requirements of the current Minecraft / MCPConfig.

## Usage

Include the source project in your workspace's `settings.gradle` via `pluginManagement.includeBuild('/path/to/YarnPatcher')`, or configure a plugin repository. Apply it in `build.gradle`:

```groovy
plugins {
    id 'yarn-patcher'
}

yarnPatcher {
    yarnVersion = '1.16.5+build.10'
    sourceDirectory = layout.projectDirectory.dir('src/main/java')
}
```

`yarnPatcher.minecraftVersion` defaults to ForgeGradle's `patcher.mcVersion`, so there is no need to set it again; `yarnVersion` still needs to be specified with the Yarn build corresponding to the Minecraft version. Keep the MCP parent project and the `patcher` Minecraft version and mapping configuration.

```powershell
# Generate Yarn sources from the raw MCP output, back up and replace the source directory.
.\gradlew.bat setupYarn

# Convert normally named sources in src that match the patcher.mappings configuration to Yarn, then compile, back up, and replace.
.\gradlew.bat remapSrcToYarn

# Only generate the src conversion output, without installing.
.\gradlew.bat remapExistingYarnSources
```

## Build

```powershell
.\gradlew.bat build validatePlugins
.\gradlew.bat publishToMavenLocal
```

The JAR is located in the `build/libs/` directory.