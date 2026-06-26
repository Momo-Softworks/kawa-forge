package com.momosoftworks.kawaforge;

import org.gradle.api.Project;
import org.gradle.api.file.FileCollection;
import org.gradle.api.tasks.SourceSet;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Knows the RetroFuturaGradle / GTNH layout where deobfuscated
 * Minecraft classes live in {@code build/classes/java/patchedMc}
 * and their sources in {@code build/rfg/minecraft-src/java}.
 */
public class RfgGtnhClasspathProvider implements MinecraftClasspathProvider {

    @Override
    public boolean applies(Project project) {
        return classDir(project).exists() || rfgRecompiledJar(project).exists();
    }

    @Override
    public FileCollection replClasspath(Project project, SourceSet main) {
        List<File> entries = new ArrayList<>();
        addIfExists(entries, classDir(project));
        addIfExists(entries, resDir(project));
        addIfExists(entries, rfgRecompiledJar(project));
        addIfExists(entries, rfgSrgMergedJar(project));
        return project.files(entries.toArray()).filter(File::exists);
    }

    @Override
    public List<File> sourceRoots(Project project) {
        File dir = new File(project.getBuildDir(), "rfg/minecraft-src/java");
        if (dir.isDirectory()) {
            return Collections.singletonList(dir);
        }
        return Collections.emptyList();
    }

    // ------------------------------------------------------------------

    private static File classDir(Project project) {
        return new File(project.getBuildDir(), "classes/java/patchedMc");
    }

    private static File resDir(Project project) {
        return new File(project.getBuildDir(), "resources/patchedMc");
    }

    private static File rfgRecompiledJar(Project project) {
        return new File(project.getBuildDir(),
                "rfg/recompiled_minecraft-1.7.10.jar");
    }

    private static File rfgSrgMergedJar(Project project) {
        return new File(project.getBuildDir(),
                "rfg/srg_merged_minecraft.jar");
    }

    private static void addIfExists(List<File> list, File f) {
        if (f.exists()) list.add(f);
    }
}
