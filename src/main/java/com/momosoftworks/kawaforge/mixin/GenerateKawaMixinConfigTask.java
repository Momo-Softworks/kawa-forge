package com.momosoftworks.kawaforge.mixin;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class GenerateKawaMixinConfigTask extends DefaultTask {

    @InputDirectory
    @org.gradle.api.tasks.Optional
    public DirectoryProperty getProcessedClassesDir() { return processedClassesDir; }
    private final DirectoryProperty processedClassesDir = getProject().getObjects().directoryProperty();

    @Input
    @org.gradle.api.tasks.Optional
    public Property<String> getMixinPackage() { return mixinPackage; }
    private final Property<String> mixinPackage = getProject().getObjects().property(String.class);

    @Input
    public Property<String> getConfigName() { return configName; }
    private final Property<String> configName = getProject().getObjects().property(String.class);

    @Input
    public Property<Boolean> getRequired() { return required; }
    private final Property<Boolean> required = getProject().getObjects().property(Boolean.class);

    @Input
    public Property<String> getMinVersion() { return minVersion; }
    private final Property<String> minVersion = getProject().getObjects().property(String.class);

    @Input
    public Property<String> getCompatibilityLevel() { return compatibilityLevel; }
    private final Property<String> compatibilityLevel = getProject().getObjects().property(String.class);

    @OutputDirectory
    public DirectoryProperty getOutputDir() { return outputDir; }
    private final DirectoryProperty outputDir = getProject().getObjects().directoryProperty();

    @TaskAction
    public void generate() {
        Path classesPath = getProcessedClassesDir().isPresent() 
            ? getProcessedClassesDir().get().getAsFile().toPath() 
            : null;

        List<String> mixins;
        try {
            mixins = (classesPath == null) 
                ? Collections.emptyList() 
                : MixinConfigGenerator.findMixinClasses(classesPath);
        } catch (IOException e) {
            throw new GradleException("Failed to scan for Kawa mixin classes", e);
        }

        if (mixins.isEmpty()) {
            File outputFolder = getOutputDir().get().getAsFile();
            if (outputFolder.exists()) {
                File targetFile = new File(outputFolder, getConfigName().get());
                if (targetFile.exists()) {
                    targetFile.delete();
                }
            }
            getLogger().lifecycle("no Kawa mixin classes found, skipping config");
            return;
        }

        String pkg = getMixinPackage().getOrElse("");
        if (pkg.isEmpty()) {
            throw new GradleException("Discovered Kawa mixin classes " + mixins + " but kawa.mixin.mixinPackage is not set.");
        }

        try {
            String json = MixinConfigGenerator.render(
                pkg, 
                mixins, 
                getRequired().get(), 
                getMinVersion().get(), 
                getCompatibilityLevel().get()
            );
            
            File outputFolder = getOutputDir().get().getAsFile();
            if (!outputFolder.exists()) {
                outputFolder.mkdirs();
            }
            
            File targetFile = new File(outputFolder, getConfigName().get());
            Files.write(targetFile.toPath(), json.getBytes());
            
            getLogger().lifecycle("Generated Kawa mixin config: {} ({} classes)", targetFile.getAbsolutePath(), mixins.size());
        } catch (MixinConfigGenerator.MixinProcessingException e) {
            throw new GradleException(e.getMessage(), e);
        } catch (IOException e) {
            throw new GradleException("Failed to write mixin config file", e);
        }
    }
}
