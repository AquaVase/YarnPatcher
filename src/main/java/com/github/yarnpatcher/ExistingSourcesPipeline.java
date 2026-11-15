package com.github.yarnpatcher;

import net.minecraftforge.gradle.common.tasks.ApplyRangeMap;
import net.minecraftforge.gradle.common.tasks.ExtractRangeMap;
import net.minecraftforge.gradle.mcp.tasks.GenerateSRG;
import org.gradle.api.Project;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.compile.JavaCompile;

/** A separate source-input pipeline: never depends on decompilation or source extraction. */
final class ExistingSourcesPipeline {
    private static final String SOURCE_REMAPPER = org.gradle.api.JavaVersion.current().isJava8()
        ? "net.minecraftforge:Srg2Source:7.0.16:fatjar" : "net.minecraftforge:Srg2Source:8.2.3:fatjar";
    static void register(Project project, YarnPatcherExtension extension, SourceSet main,
                         TaskProvider<StageMappingsTask> classMappings,
                         TaskProvider<ExportSrgYarnTask> csv) {
        org.gradle.api.file.DirectoryProperty build = project.getLayout().getBuildDirectory();
        org.gradle.api.tasks.TaskProvider<net.minecraftforge.gradle.mcp.tasks.GenerateSRG> namedToSrg = project.getTasks().named("createMcp2Srg", GenerateSRG.class);
        org.gradle.api.tasks.TaskProvider<net.minecraftforge.gradle.common.tasks.ExtractRangeMap> ranges = project.getTasks().register("extractExistingSourceRanges", ExtractRangeMap.class, t -> {
            t.getTool().set(SOURCE_REMAPPER);
            t.setRuntimeJavaVersion(Integer.parseInt(org.gradle.api.JavaVersion.current().getMajorVersion()));
            t.setGroup(YarnPatcherPlugin.NAME);
            t.setDescription("Resolve declarations and references in existing named MCP/Mojang sources.");
            t.getSources().from(extension.getSourceDirectory());
            t.getDependencies().from(main.getCompileClasspath());
            t.getSourceCompatibility().set(project.provider(() -> project.getExtensions()
                    .getByType(org.gradle.api.plugins.JavaPluginExtension.class).getSourceCompatibility().toString()));
            t.getOutput().set(build.file("yarnpatcher/existing/ranges.txt"));
        });
        org.gradle.api.tasks.TaskProvider<net.minecraftforge.gradle.common.tasks.ApplyRangeMap> normalize = project.getTasks().register("normalizeExistingSourcesToSrg", ApplyRangeMap.class, t -> {
            t.getTool().set(SOURCE_REMAPPER);
            t.setRuntimeJavaVersion(Integer.parseInt(org.gradle.api.JavaVersion.current().getMajorVersion()));
            t.setGroup(YarnPatcherPlugin.NAME);
            t.setDescription("Use ForgeGradle source ranges to restore SRG member identifiers without global name replacement.");
            t.getSources().from(extension.getSourceDirectory());
            t.getSrgFiles().from(namedToSrg.flatMap(GenerateSRG::getOutput));
            t.getRangeMap().set(ranges.flatMap(ExtractRangeMap::getOutput));
            t.getOutput().set(build.file("yarnpatcher/existing/srg.zip"));
        });
        org.gradle.api.tasks.TaskProvider<com.github.yarnpatcher.PrepareExistingSrgSourcesTask> prepared = project.getTasks().register("prepareExistingSrgSources", PrepareExistingSrgSourcesTask.class, t -> {
            t.setGroup(YarnPatcherPlugin.NAME);
            t.getOriginalSources().set(extension.getSourceDirectory());
            t.getSourceArchive().set(normalize.flatMap(ApplyRangeMap::getOutput));
            t.getNamedToSrg().set(namedToSrg.flatMap(GenerateSRG::getOutput));
            t.getClasspath().from(main.getCompileClasspath());
            t.getOutputDirectory().set(build.dir("yarnpatcher/existing/srg"));
        });
        org.gradle.api.tasks.TaskProvider<com.github.yarnpatcher.RemapSourcesTask> classes = project.getTasks().register("remapExistingYarnClasses", RemapSourcesTask.class, t -> {
            t.setGroup(YarnPatcherPlugin.NAME);
            t.getSourceDirectory().set(prepared.flatMap(PrepareExistingSrgSourcesTask::getOutputDirectory));
            t.getMappingsFile().set(classMappings.flatMap(StageMappingsTask::getOutputFile));
            t.getClasspath().from(main.getCompileClasspath());
            t.getOutputDirectory().set(build.dir("yarnpatcher/existing/classes"));
        });
        org.gradle.api.tasks.TaskProvider<com.github.yarnpatcher.RemapSrgMembersTask> fields = project.getTasks().register("remapExistingYarnFields", RemapSrgMembersTask.class, t -> {
            t.setGroup(YarnPatcherPlugin.NAME);
            t.getSourceDirectory().set(classes.flatMap(RemapSourcesTask::getOutputDirectory));
            t.getCsvFiles().from(csv.flatMap(c -> c.getOutputDirectory().file("fields.csv")));
            t.getOutputDirectory().set(build.dir("yarnpatcher/existing/fields"));
        });
        org.gradle.api.tasks.TaskProvider<com.github.yarnpatcher.GenerateSafeMethodsTask> safe = project.getTasks().register("generateExistingSafeMethodNames", GenerateSafeMethodsTask.class, t -> {
            t.setGroup(YarnPatcherPlugin.NAME);
            t.getSourceDirectory().set(fields.flatMap(RemapSrgMembersTask::getOutputDirectory));
            t.getMethodsCsv().set(csv.flatMap(c -> c.getOutputDirectory().file("methods.csv")));
            t.getClasspath().from(main.getCompileClasspath());
            t.getOutputDirectory().set(build.dir("yarnpatcher/existing/safe-methods"));
        });
        org.gradle.api.tasks.TaskProvider<com.github.yarnpatcher.RemapSrgMembersTask> methods = project.getTasks().register("remapExistingYarnMethods", RemapSrgMembersTask.class, t -> {
            t.setGroup(YarnPatcherPlugin.NAME);
            t.getSourceDirectory().set(fields.flatMap(RemapSrgMembersTask::getOutputDirectory));
            t.getCsvFiles().from(safe.flatMap(c -> c.getOutputDirectory().file("methods.csv")));
            t.getOutputDirectory().set(build.dir("yarnpatcher/existing/methods"));
        });
        org.gradle.api.tasks.TaskProvider<com.github.yarnpatcher.RemapSrgMembersTask> remap = project.getTasks().register("remapExistingYarnSources", RemapSrgMembersTask.class, t -> {
            t.setGroup(YarnPatcherPlugin.NAME);
            t.setDescription("Remap current src from patcher.mappings names to Yarn in build/yarnpatcher/existing/yarn.");
            t.getSourceDirectory().set(methods.flatMap(RemapSrgMembersTask::getOutputDirectory));
            t.getCsvFiles().from(csv.flatMap(c -> c.getOutputDirectory().file("params.csv")));
            t.getOrganizeImports().set(true);
            t.getClasspath().from(main.getCompileClasspath());
            t.getOutputDirectory().set(build.dir("yarnpatcher/existing/yarn"));
        });
        org.gradle.api.tasks.TaskProvider<org.gradle.api.tasks.compile.JavaCompile> compile = project.getTasks().register("compileExistingYarnSources", JavaCompile.class, t -> {
            t.setGroup("verification");
            t.setDescription("Compile the Yarn conversion of existing src before installation.");
            t.source(remap.map(task -> project.fileTree(task.getOutputDirectory()).matching(p -> p.include("**/*.java"))));
            t.setClasspath(main.getCompileClasspath());
            t.getDestinationDirectory().set(build.dir("yarnpatcher/existing/compiled"));
            t.getJavaCompiler().set(project.getTasks().named("compileJava", JavaCompile.class).flatMap(JavaCompile::getJavaCompiler));
            t.getOptions().setEncoding("UTF-8");
        });
        project.getTasks().register("remapSrcToYarn", InstallSourcesTask.class, t -> {
            t.setGroup(YarnPatcherPlugin.NAME);
            t.setDescription("Convert existing named MCP/Mojang src to Yarn, compile, back up and replace src.");
            t.dependsOn(compile);
            t.getStagedDirectory().set(remap.flatMap(RemapSrgMembersTask::getOutputDirectory));
            t.getDestinationDirectory().set(extension.getSourceDirectory());
            t.getBackupRoot().set(project.getLayout().getProjectDirectory().dir(".yarnpatcher/backups"));
        });
    }
}
