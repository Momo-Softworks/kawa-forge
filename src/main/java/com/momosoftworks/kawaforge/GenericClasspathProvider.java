package com.momosoftworks.kawaforge;

import org.gradle.api.Project;
import org.gradle.api.file.FileCollection;
import org.gradle.api.tasks.SourceSet;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Fallback provider for projects that do not look like Minecraft Forge.
 * Returns the standard Java source roots so {@code M-.} can open them.
 */
public class GenericClasspathProvider implements MinecraftClasspathProvider {

    @Override
    public boolean applies(Project project) {
        // Always applies as fallback.
        return true;
    }

    @Override
    public FileCollection replClasspath(Project project, SourceSet main) {
        // No additional Minecraft-specific entries.
        return project.files();
    }

    @Override
    public List<File> sourceRoots(Project project) {
        List<File> roots = new ArrayList<>();
        addIfExists(roots, project.file("src/main/java"));
        addIfExists(roots, project.file("src/main/kawa"));
        addIfExists(roots, project.file("src/main/scheme"));
        addIfExists(roots, project.file("src"));
        return roots;
    }

    private static void addIfExists(List<File> list, File f) {
        if (f.isDirectory()) list.add(f);
    }
}
