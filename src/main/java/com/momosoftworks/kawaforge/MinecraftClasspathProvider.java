package com.momosoftworks.kawaforge;

import org.gradle.api.Project;
import org.gradle.api.file.FileCollection;
import org.gradle.api.tasks.SourceSet;

import java.io.File;
import java.util.List;

/**
 * Provides the classpath and source roots that a Minecraft mod
 * developer needs in their Kawa REPL.
 *
 * Different eras of ForgeGradle lay out recompiled/patched Minecraft
 * classes and sources in different directories.  Each implementation
 * knows one layout.
 */
public interface MinecraftClasspathProvider {

    /** Whether this provider recognises the current project setup. */
    boolean applies(Project project);

    /**
     * Additional classpath entries beyond the main source set
     * runtime classpath that are needed for Minecraft development
     * (e.g. patched/deobfuscated Minecraft classes).
     */
    FileCollection replClasspath(Project project, SourceSet main);

    /**
     * Source directories that correspond to the Minecraft classes
     * on the classpath, so that {@code M-.} in Emacs can open them.
     * May be empty.
     */
    List<File> sourceRoots(Project project);
}
