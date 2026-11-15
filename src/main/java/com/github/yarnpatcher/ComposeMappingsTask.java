package com.github.yarnpatcher;

import java.util.stream.Collectors;

import net.minecraftforge.srgutils.IMappingFile;
import org.gradle.api.*;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

@CacheableTask
public abstract class ComposeMappingsTask extends DefaultTask {
    @InputFile @PathSensitive(PathSensitivity.NONE) public abstract RegularFileProperty getObfuscatedToSrg();
    @InputFile @PathSensitive(PathSensitivity.NONE) public abstract RegularFileProperty getYarnArchive();
    @InputFile @PathSensitive(PathSensitivity.NONE) public abstract RegularFileProperty getIntermediaryArchive();
    @InputFile @Optional @PathSensitive(PathSensitivity.NONE) public abstract RegularFileProperty getMcpConfig();
    @Input public abstract Property<String> getMinecraftVersion();
    @Input public abstract Property<String> getYarnVersion();
    @OutputFile public abstract RegularFileProperty getOutputFile();

    @TaskAction public void compose() throws IOException {
        if (!getYarnVersion().get().startsWith(getMinecraftVersion().get() + "+build.")) {
        throw new GradleException("yarnVersion must belong to minecraftVersion, e.g. 1.21.1+build.3");
        }
        compose(IMappingFile.load(getObfuscatedToSrg().get().getAsFile()).reverse(), getIntermediaryArchive().get().getAsFile().toPath(),
                getYarnArchive().get().getAsFile().toPath(), getOutputFile().get().getAsFile().toPath(),
                LegacyMcpParameters.read(getMcpConfig().isPresent() ? getMcpConfig().get().getAsFile().toPath() : null));
    }

    static void compose(Path bridgePath, Path intermediaryPath, Path yarnPath, Path output) throws IOException {
        compose(IMappingFile.load(bridgePath.toFile()), intermediaryPath, yarnPath, output);
    }

    static void compose(IMappingFile bridge, Path intermediaryPath, Path yarnPath, Path output) throws IOException {
        compose(bridge, intermediaryPath, yarnPath, output, LegacyMcpParameters.read(null));
    }

    static void compose(IMappingFile bridge, Path intermediaryPath, Path yarnPath, Path output, LegacyMcpParameters legacy) throws IOException {
        IMappingFile reverseBridge = bridge.reverse();
        MappingIndex intermediary = MappingIndex.archive(intermediaryPath, "official", "intermediary");
        MappingIndex yarn = MappingIndex.archive(yarnPath, "intermediary", "named");
        java.util.Map<String, String> targetClasses = new java.util.HashMap<>();
        for (net.minecraftforge.srgutils.IMappingFile.IClass cls : bridge.getClasses()) {
            String inter = intermediary.map(cls.getMapped());
            targetClasses.put(cls.getOriginal(), intermediary.classes.containsKey(cls.getMapped()) || yarn.classes.containsKey(inter)
                              ? yarn.map(inter) : cls.getOriginal());
        }
        // Yarn may omit a named entry for a nested class while naming its outer class.
        for (String name : targetClasses.keySet().stream().sorted(java.util.Comparator.comparingInt(String::length)).collect(Collectors.toList())) {
            int nested = name.lastIndexOf('$');
            if (nested < 0) continue;
            String outer = targetClasses.get(name.substring(0, nested));
            String target = targetClasses.get(name);
            if (outer != null && target.contains("$")) targetClasses.put(name, outer + target.substring(target.lastIndexOf('$')));
        }
        StringBuilder tiny = new StringBuilder("tiny\t2\t0\tsrg\tyarn\n");
        int connected = 0;
        for (net.minecraftforge.srgutils.IMappingFile.IClass cls : bridge.getClasses()) {
            String obfOwner = cls.getMapped();
            String interOwner = intermediary.map(obfOwner);
            String namedOwner = targetClasses.get(cls.getOriginal());
            // Classes absent from Fabric (e.g. injected Forge classes) keep their original names.
            boolean mapped = intermediary.classes.containsKey(obfOwner) || yarn.classes.containsKey(interOwner);
            if (mapped) connected++;
            tiny.append("c\t").append(cls.getOriginal()).append('\t').append(mapped ? namedOwner : cls.getOriginal()).append('\n');
            if (!mapped) continue;
            for (net.minecraftforge.srgutils.IMappingFile.IField field : cls.getFields()) {
                String obfDesc = field.getMappedDescriptor();
                if (obfDesc == null) {
                    java.util.Set<java.lang.String> descriptors = intermediary.fieldDescriptors.get(obfOwner + "." + field.getMapped());
                    if (descriptors == null || descriptors.isEmpty()) continue; // Absent Fabric member keeps its Mojang name.
                    if (descriptors.size() != 1) throw new IOException("Ambiguous descriptor-less TSRG field: " + cls.getOriginal() + "." + field.getOriginal());
                    obfDesc = descriptors.iterator().next();
                }
                if (!intermediary.fields.containsKey(new MappingIndex.Member(obfOwner, field.getMapped(), obfDesc))) continue;
                String interName = intermediary.mapFieldName(obfOwner, field.getMapped(), obfDesc);
                String named = yarn.mapFieldName(interOwner, interName, intermediary.mapDesc(obfDesc));
                tiny.append("\tf\t").append(reverseBridge.remapDescriptor(obfDesc)).append('\t').append(field.getOriginal()).append('\t').append(named).append('\n');
            }
            for (net.minecraftforge.srgutils.IMappingFile.IMethod method : cls.getMethods()) {
                String obfDesc = method.getMappedDescriptor();
                if (!intermediary.methods.containsKey(new MappingIndex.Member(obfOwner, method.getMapped(), obfDesc)) && !method.getOriginal().equals("<init>")) continue;
                String interName = intermediary.mapMethodName(obfOwner, method.getMapped(), obfDesc);
                String named = yarn.mapMethodName(interOwner, interName, intermediary.mapMethodDesc(obfDesc));
                tiny.append("\tm\t").append(method.getDescriptor()).append('\t').append(method.getOriginal()).append('\t').append(named).append('\n');
                boolean oldMethod = method.getOriginal().startsWith("func_");
                int slot = oldMethod ? (legacy.statics.contains(method.getOriginal()) ? 0 : 1)
                           : method.getOriginal().equals("<init>") || !method.getMetadata().containsKey("is_static") ? 1 : 0;
                org.objectweb.asm.Type[] argumentTypes = org.objectweb.asm.Type.getArgumentTypes(obfDesc);
                for (int position = 0; position < argumentTypes.length; position++) {
                    net.minecraftforge.srgutils.IMappingFile.IParameter parameter = method.getParameter(position);
                    String originalParameter = parameter == null ? null : parameter.getOriginal();
                    if (originalParameter == null && oldMethod) originalParameter = "p_" + method.getOriginal().split("_")[1] + "_" + slot + "_";
                    String parameterName = yarn.arguments.get(new MappingIndex.Argument(new MappingIndex.Member(interOwner, interName, intermediary.mapMethodDesc(obfDesc)), slot));
                    if (originalParameter != null && parameterName != null && SrgNames.parameter(originalParameter))
                        tiny.append("\t\tp\t").append(slot).append('\t').append(originalParameter).append('\t').append(parameterName).append('\n');
                    slot += argumentTypes[position].getSize();
                }
            }
            for (com.github.yarnpatcher.LegacyMcpParameters.Constructor constructor : legacy.constructors.getOrDefault(cls.getOriginal(), Jdk8Compat.list())) {
                String obfDesc = bridge.remapDescriptor(constructor.descriptor());
                tiny.append("\tm\t").append(constructor.descriptor()).append("\t<init>\t<init>\n");
                int slot = 1;
                for (org.objectweb.asm.Type argument : org.objectweb.asm.Type.getArgumentTypes(obfDesc)) {
                    String name = yarn.arguments.get(new MappingIndex.Argument(new MappingIndex.Member(interOwner, "<init>", intermediary.mapMethodDesc(obfDesc)), slot));
                    if (name != null) tiny.append("\t\tp\t").append(slot).append("\tp_i").append(constructor.id()).append('_').append(slot).append("_\t").append(name).append('\n');
                    slot += argument.getSize();
                }
            }
        }
        if (connected == 0) throw new IOException("No classes connect Mojang and Intermediary mappings. Check version and mapping direction.");
        Files.createDirectories(output.toAbsolutePath().getParent());
        Jdk8Compat.writeString(output, tiny, StandardCharsets.UTF_8);
    }
}
