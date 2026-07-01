package com.momosoftworks.kawaforge.mixin;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

public class KawaMixinProcessTask extends DefaultTask {

    @InputDirectory
    @SkipWhenEmpty
    @IgnoreEmptyDirectories
    public DirectoryProperty getInputClassesDir() { return inputClassesDir; }
    private final DirectoryProperty inputClassesDir = getProject().getObjects().directoryProperty();

    @Classpath
    public ConfigurableFileCollection getDefinitionClasspath() { return definitionClasspath; }
    private final ConfigurableFileCollection definitionClasspath = getProject().getObjects().fileCollection();

    @OutputDirectory
    public DirectoryProperty getOutputClassesDir() { return outputClassesDir; }
    private final DirectoryProperty outputClassesDir = getProject().getObjects().directoryProperty();

    @TaskAction
    public void process() {
        File inputDir = getInputClassesDir().get().getAsFile();
        File outputDir = getOutputClassesDir().get().getAsFile();

        // Clear and recreate output dir
        try {
            if (outputDir.exists()) {
                Files.walk(outputDir.toPath())
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to clear output directory: " + outputDir, e);
        }
        outputDir.mkdirs();

        // Build processor classpath: provided defs + input classes dir
        List<Path> processorCp = new ArrayList<>();
        for (File f : getDefinitionClasspath().getFiles()) {
            processorCp.add(f.toPath());
        }
        processorCp.add(inputDir.toPath());

        MixinClassProcessor processor = new MixinClassProcessor(processorCp);
        
        int[] stats = {0, 0}; // {scanned, transformed}

        try {
            Files.walk(inputDir.toPath())
                .filter(p -> !Files.isDirectory(p))
                .forEach(p -> {
                    Path relative = inputDir.toPath().relativize(p);
                    Path target = outputDir.toPath().resolve(relative);
                    
                    try {
                        Files.createDirectories(target.getParent());
                        if (p.toString().endsWith(".class")) {
                            stats[0]++;
                            byte[] bytes = Files.readAllBytes(p);
                            byte[] processed = processor.process(bytes);
                            Files.write(target, processed != null ? processed : bytes);
                            if (processed != null) stats[1]++;
                        } else {
                            Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (IOException e) {
                        throw new RuntimeException("IO error processing " + p, e);
                    }
                });
        } catch (IOException e) {
            throw new RuntimeException("Failed to walk input directory", e);
        }

        getLogger().lifecycle("Kawa Mixin: scanned {} classes, transformed {} {}:{}", 
            stats[0], stats[1], inputDir.getAbsolutePath(), outputDir.getAbsolutePath());
    }
}
