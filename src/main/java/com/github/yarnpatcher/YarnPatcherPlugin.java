package com.github.yarnpatcher;

import net.minecraftforge.gradle.common.tasks.ExtractZip;
import net.minecraftforge.gradle.mcp.tasks.GenerateSRG;
import net.minecraftforge.gradle.mcp.tasks.DownloadMCPConfig;
import net.minecraftforge.gradle.mcp.tasks.SetupMCP;
import net.minecraftforge.gradle.patcher.PatcherExtension;
import net.minecraftforge.gradle.patcher.PatcherPlugin;
import org.gradle.api.*;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.Directory;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.compile.JavaCompile;

public final class YarnPatcherPlugin implements Plugin<Project> {
    public static final String NAME = "YarnPatcher";

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(PatcherPlugin.class);
        PatcherExtension patcher = project.getExtensions().getByType(PatcherExtension.class);
        YarnPatcherExtension extension = project.getExtensions().create("yarnPatcher", YarnPatcherExtension.class);
        extension.getMinecraftVersion().convention(patcher.getMcVersion());
        extension.getSourceDirectory().convention(project.getLayout().getProjectDirectory().dir("src/main/java"));
        patcher.getPatchedSrc().set(extension.getSourceDirectory());

        project.getRepositories().maven(r -> r.setUrl("https://maven.fabricmc.net"));
        Configuration yarn = mappingConfiguration(project, "yarnPatcherYarn");
        Configuration intermediary = mappingConfiguration(project, "yarnPatcherIntermediary");
        yarn.getDependencies().addLater(extension.getYarnVersion().map(v ->
                project.getDependencies().create("net.fabricmc:yarn:" + v + ":v2")));
        intermediary.getDependencies().addLater(extension.getMinecraftVersion().map(v ->
                project.getDependencies().create("net.fabricmc:intermediary:" + v + ":v2")));

        // Extracting directly into src would overwrite edits before Rewrite can succeed.
        TaskProvider<ExtractZip> extract = project.getTasks().named("extractMapped", ExtractZip.class);
        extract.configure(t -> t.getOutput().set(project.getLayout().getBuildDirectory().dir("yarnpatcher/mojang")));
        TaskProvider<GenerateSRG> bridge = project.getTasks().named("createMcp2Obf", GenerateSRG.class);
        TaskProvider<ComposeMappingsTask> mappings = project.getTasks().register("composeYarnMappings", ComposeMappingsTask.class, t -> {
            t.setGroup(YarnPatcherPlugin.NAME);
            t.setDescription("Compose SRG -> obfuscated -> intermediary -> Yarn mappings.");
            t.getObfuscatedToSrg().set(bridge.flatMap(GenerateSRG::getSrg));
            t.getYarnArchive().set(project.getLayout().file(yarn.getElements().map(files -> files.iterator().next().getAsFile())));
            t.getIntermediaryArchive().set(project.getLayout().file(intermediary.getElements().map(files -> files.iterator().next().getAsFile())));
            t.getMinecraftVersion().set(extension.getMinecraftVersion());
            t.getYarnVersion().set(extension.getYarnVersion());
            t.getOutputFile().set(project.getLayout().getBuildDirectory().file("yarnpatcher/srg2yarn.tiny"));
        });
        SourceSet main = project.getExtensions().getByType(JavaPluginExtension.class).getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        TaskProvider<StageMappingsTask> classMappings = project.getTasks().register("prepareYarnClassesMappings", StageMappingsTask.class, t -> {
            t.getMappingsFile().set(mappings.flatMap(ComposeMappingsTask::getOutputFile));
            t.getStage().set("classes");
            t.getOutputFile().set(project.getLayout().getBuildDirectory().file("yarnpatcher/classes.tiny"));
        });
        TaskProvider<ExportSrgYarnTask> csv = project.getTasks().register("generateSrg2Yarn", ExportSrgYarnTask.class, t -> {
            t.setGroup(YarnPatcherPlugin.NAME);
            t.setDescription("Export SRG to Yarn fields.csv, methods.csv and params.csv for the built-in source remapper.");
            t.getMappingsFile().set(mappings.flatMap(ComposeMappingsTask::getOutputFile));
            t.getOutputDirectory().set(project.getLayout().getBuildDirectory().dir("yarnpatcher/srg2yarn"));
        });
        ExistingSourcesPipeline.register(project, extension, main, classMappings, csv);
        TaskProvider<RemapSourcesTask> classes = project.getTasks().register("remapYarnClasses", RemapSourcesTask.class, t -> {
            t.setGroup(YarnPatcherPlugin.NAME);
            t.setDescription("Use OpenRewrite only for class, package and constructor names from setupMCP/output.zip.");
            t.getMappingsFile().set(classMappings.flatMap(StageMappingsTask::getOutputFile));
            t.getClasspath().from(main.getCompileClasspath());
            t.getOutputDirectory().set(project.getLayout().getBuildDirectory().dir("yarnpatcher/classes"));
        });
        TaskProvider<RemapSrgMembersTask> fields = project.getTasks().register("remapYarnFields", RemapSrgMembersTask.class, t -> {
            t.setGroup(YarnPatcherPlugin.NAME);
            t.getSourceDirectory().set(classes.flatMap(RemapSourcesTask::getOutputDirectory));
            t.getCsvFiles().from(csv.flatMap(c -> c.getOutputDirectory().file("fields.csv")));
            t.getOutputDirectory().set(project.getLayout().getBuildDirectory().dir("yarnpatcher/fields"));
        });
        TaskProvider<GenerateSafeMethodsTask> safeMethods = project.getTasks().register("generateSafeMethodNames", GenerateSafeMethodsTask.class, t -> {
            t.setGroup(YarnPatcherPlugin.NAME);
            t.setDescription("Analyze conflicts with OpenRewrite and generate method CSV names with $ suffixes.");
            t.getSourceDirectory().set(fields.flatMap(RemapSrgMembersTask::getOutputDirectory));
            t.getMethodsCsv().set(csv.flatMap(c -> c.getOutputDirectory().file("methods.csv")));
            t.getClasspath().from(main.getCompileClasspath());
            t.getOutputDirectory().set(project.getLayout().getBuildDirectory().dir("yarnpatcher/safe-methods"));
        });
        TaskProvider<RemapSrgMembersTask> methods = project.getTasks().register("remapYarnMethods", RemapSrgMembersTask.class, t -> {
            t.setGroup(YarnPatcherPlugin.NAME);
            t.setDescription("Apply conflict-safe method CSV mappings with the identifier replacement backend.");
            t.getSourceDirectory().set(fields.flatMap(RemapSrgMembersTask::getOutputDirectory));
            t.getCsvFiles().from(safeMethods.flatMap(c -> c.getOutputDirectory().file("methods.csv")));
            t.getOutputDirectory().set(project.getLayout().getBuildDirectory().dir("yarnpatcher/methods"));
        });
        TaskProvider<RemapSrgMembersTask> remap = project.getTasks().register("remapYarnSources", RemapSrgMembersTask.class, t -> {
            t.setGroup(YarnPatcherPlugin.NAME);
            t.setDescription("Apply short parameter CSV names and organize imports after method remapping.");
            t.getSourceDirectory().set(methods.flatMap(RemapSrgMembersTask::getOutputDirectory));
            t.getCsvFiles().from(csv.flatMap(c -> c.getOutputDirectory().file("params.csv")));
            t.getOrganizeImports().set(true);
            t.getClasspath().from(main.getCompileClasspath());
            t.getOutputDirectory().set(project.getLayout().getBuildDirectory().dir("yarnpatcher/yarn"));
        });
        for (String stage : Jdk8Compat.list("classes", "fields", "methods", "parameters")) {
            String title = stage.substring(0, 1).toUpperCase() + stage.substring(1);
            Provider<Directory> sources = stage.equals("classes") ? classes.flatMap(RemapSourcesTask::getOutputDirectory)
                                          : stage.equals("fields") ? fields.flatMap(RemapSrgMembersTask::getOutputDirectory)
                                          : stage.equals("methods") ? methods.flatMap(RemapSrgMembersTask::getOutputDirectory) : remap.flatMap(RemapSrgMembersTask::getOutputDirectory);
            project.getTasks().register("compileYarn" + title, JavaCompile.class, t -> {
                t.setGroup("verification");
                t.source(sources.map(d -> project.fileTree(d).matching(pattern -> pattern.include("**/*.java"))));
                t.setClasspath(main.getCompileClasspath());
                t.getDestinationDirectory().set(project.getLayout().getBuildDirectory().dir("yarnpatcher/compiled-" + stage));
                t.getJavaCompiler().set(project.getTasks().named("compileJava", JavaCompile.class).flatMap(JavaCompile::getJavaCompiler));
                t.getOptions().setEncoding("UTF-8");
                t.getOptions().getCompilerArgs().addAll(Jdk8Compat.list("-Xmaxerrs", "1000", "-Xdiags:verbose"));
            });
        }
        TaskProvider<InstallSourcesTask> install = project.getTasks().register("setupYarn", InstallSourcesTask.class, t -> {
            t.setGroup(YarnPatcherPlugin.NAME);
            t.setDescription("Back up and replace the source directory with successfully rewritten Yarn sources.");
            t.getStagedDirectory().set(remap.flatMap(RemapSrgMembersTask::getOutputDirectory));
            t.getDestinationDirectory().set(extension.getSourceDirectory());
            t.getBackupRoot().set(project.getLayout().getProjectDirectory().dir(".yarnpatcher/backups"));
        });
        // MCP-Reborn's existing setup task also installs Yarn; compiling never resets user edits.
        project.getTasks().matching(t -> t.getName().equals("setup")).configureEach(t -> t.dependsOn(install));
        project.afterEvaluate(p -> {
            Project parent = patcher.getParent().getOrNull();
            while (parent != null && !parent.getPlugins().hasPlugin("net.minecraftforge.gradle.mcp")) {
                PatcherExtension ancestor = parent.getExtensions().findByType(PatcherExtension.class);
                parent = ancestor == null ? null : ancestor.getParent().getOrNull();
            }
            if (parent == null) throw new GradleException(YarnPatcherPlugin.NAME + " requires an MCP parent with setupMCP.");
            TaskProvider<SetupMCP> setup = parent.getTasks().named("setupMCP", SetupMCP.class);
            TaskProvider<DownloadMCPConfig> config = parent.getTasks().named("downloadConfig", DownloadMCPConfig.class);
            mappings.configure(t -> t.getMcpConfig().set(config.flatMap(DownloadMCPConfig::getOutput)));
            classes.configure(t -> t.getSourceArchive().set(setup.flatMap(SetupMCP::getOutput)));
            if (!extension.getMinecraftVersion().get().equals(patcher.getMcVersion().get()) ||
                    "official".equals(patcher.getMappingChannel().getOrNull()) &&
                    !extension.getMinecraftVersion().get().equals(patcher.getMappingVersion().get())) {
                throw new GradleException(YarnPatcherPlugin.NAME + " minecraftVersion and patcher mcVersion must match; official mapping version must also match.");
            }
        });
    }

    private Configuration mappingConfiguration(Project project, String name) {
        Configuration configuration = project.getConfigurations().create(name);
        configuration.setCanBeConsumed(false);
        configuration.setCanBeResolved(true);
        configuration.setTransitive(false);
        return configuration;
    }
}
