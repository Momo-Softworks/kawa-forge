package com.momosoftworks.kawaforge;

import org.gradle.api.*;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskProvider;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class KawaForgePlugin implements Plugin<Project> {

    private static final List<MinecraftClasspathProvider> PROVIDERS = new ArrayList<>();

    static {
        PROVIDERS.add(new RfgGtnhClasspathProvider());
        PROVIDERS.add(new GenericClasspathProvider());
    }

    private static final String ANNOTATIONS_VERSION = "0.3.1"; // keep in lockstep with the root project version until the plugin publishes both together

    @Override
    public void apply(Project project) {
        KawaForgeExtension ext = project.getExtensions().create("kawa", KawaForgeExtension.class);
        project.getPlugins().withId("java", plugin -> configureKawa(project, ext));
    }

    private void configureKawa(Project project, KawaForgeExtension ext) {
        // ---- Kawa runtime ----
        Configuration kawaConfig = project.getConfigurations().create("kawaRuntime");
        kawaConfig.setDescription("Kawa runtime and compiler");
        project.getDependencies().add(kawaConfig.getName(), resolveKawaDependency(project, ext.getRuntime()));

        project.getConfigurations().getByName("compileClasspath").extendsFrom(kawaConfig);
        project.getConfigurations().getByName("runtimeClasspath").extendsFrom(kawaConfig);

        // Resolve mixin carrier annotations from the kawa-forge maven branch
        // (auto-added below, like the kawa-runtime repo); mavenLocal also works
        // during development.
        addKawaForgeMavenRepo(project);
        project.getDependencies().add("compileOnly", "com.momosoftworks:kawa-mixin-annotations:" + ANNOTATIONS_VERSION);

        // ---- Source / output ----
        File schemeSourceDir = project.file(ext.getSourceDir());
        org.gradle.api.provider.Provider<org.gradle.api.file.Directory> schemeOutputDir =
            project.getLayout().getBuildDirectory().dir("classes/kawa/main");

        JavaPluginExtension javaExt = project.getExtensions().getByType(JavaPluginExtension.class);
        SourceSet mainSourceSet = javaExt.getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);

        // ---- compileKawa task ----
        TaskProvider<KawaCompileTask> compileKawa = project.getTasks().register(
            "compileKawa", KawaCompileTask.class, task -> {
                task.setDescription("AOT-compile all Kawa Scheme sources to JVM class files");
                task.setGroup("build");
                task.setSchemeSourceDir(schemeSourceDir);
                task.getSchemeOutputDir().set(schemeOutputDir);
                task.setMainSourceSet(mainSourceSet);
                task.setCompilerArgs(ext.getCompilerArgs());
                task.setVerbosity(ext.getVerbosity());

                task.onlyIf(new Spec<Task>() {
                    @Override
                    public boolean isSatisfiedBy(Task t) {
                        return schemeSourceDir.exists() && hasScmFiles(schemeSourceDir);
                    }
                });
            });

        // ---- processKawaMixins task ----
        org.gradle.api.provider.Provider<org.gradle.api.file.Directory> processedOutputDir =
            project.getLayout().getBuildDirectory().dir("classes/kawaMixin/main");
        TaskProvider<com.momosoftworks.kawaforge.mixin.KawaMixinProcessTask> processKawaMixins = project.getTasks().register(
            "processKawaMixins", com.momosoftworks.kawaforge.mixin.KawaMixinProcessTask.class, task -> {
                task.setDescription("Materialize Mixin annotations from Kawa carrier payloads into compiled classes");
                task.setGroup("build");
                task.getInputClassesDir().set(schemeOutputDir);
                task.getOutputClassesDir().set(processedOutputDir);
                task.getDefinitionClasspath().from(project.getConfigurations().getByName("compileClasspath"));
                task.dependsOn(compileKawa);
            });

        // ---- generateKawaMixinConfig task ----
        // Registered unconditionally: the extension is not yet configured at
        // plugin-apply time, so `enabled` must be consulted lazily (onlyIf).
        // The register callback itself only runs at task realization, i.e.
        // after the consumer build script has configured the extension.
        org.gradle.api.provider.Provider<org.gradle.api.file.Directory> generatedResourcesDir =
            project.getLayout().getBuildDirectory().dir("generated/kawaMixin/resources");
        TaskProvider<com.momosoftworks.kawaforge.mixin.GenerateKawaMixinConfigTask> generateKawaMixinConfig = project.getTasks().register(
            "generateKawaMixinConfig", com.momosoftworks.kawaforge.mixin.GenerateKawaMixinConfigTask.class, task -> {
                task.setDescription("Automatically generate mixins.json for Kawa-authored mixins");
                task.setGroup("build");
                task.getProcessedClassesDir().set(processedOutputDir);
                task.getOutputDir().set(generatedResourcesDir);
                task.getMixinPackage().set(ext.getMixin().getMixinPackage());
                task.getRequired().set(ext.getMixin().isRequired());
                task.getMinVersion().set(ext.getMixin().getMinVersion());
                task.getCompatibilityLevel().set(ext.getMixin().getCompatibilityLevel());

                String defaultName = "mixins." + project.getName() + ".json";
                String configName = ext.getMixin().getConfigName().isEmpty() ? defaultName : ext.getMixin().getConfigName();
                task.getConfigName().set(configName);

                task.onlyIf(t -> ext.getMixin().isEnabled());
                task.dependsOn(processKawaMixins);
            });

        mainSourceSet.getOutput().dir(
            java.util.Collections.singletonMap("builtBy", generateKawaMixinConfig),
            generatedResourcesDir);

        // ---- Wire into main ----
        mainSourceSet.getOutput().dir(
            java.util.Collections.singletonMap("builtBy", processKawaMixins),
            processedOutputDir);
        project.getTasks().getByName("compileJava").dependsOn(processKawaMixins);

        // ---- Pick the best classpath provider ----
        final MinecraftClasspathProvider minecraftProvider;
        MinecraftClasspathProvider selected = null;
        for (MinecraftClasspathProvider p : PROVIDERS) {
            if (p.applies(project)) {
                selected = p;
                break;
            }
        }
        minecraftProvider = selected;

        // ---- kawaRepl task ----
        int standalonePort = ext.getRepl().getStandalonePort();
        if (standalonePort > 0) {
            project.getTasks().register("kawaRepl", org.gradle.api.tasks.JavaExec.class, task -> {
                task.setDescription("Start a Kawa REPL with the mod's full classpath");
                task.setGroup("development");
                task.setStandardInput(System.in);

                // Assemble full REPL classpath: main runtime + Kawa output + Minecraft.
                org.gradle.api.file.FileCollection classpath = mainSourceSet.getRuntimeClasspath()
                    .plus(project.files(processedOutputDir))
                    .plus(minecraftProvider.replClasspath(project, mainSourceSet));
                task.setClasspath(classpath.filter(File::exists));

                // Resolve geiser-kawa scheme sources.
                File geiserSchemeDir = resolveGeiserKawaSchemeDir(project);

                task.getMainClass().set("kawa.repl");
                List<String> args = new ArrayList<>();

                if (geiserSchemeDir != null) {
                    args.add("-Dkawa.import.path=" + geiserSchemeDir.getAbsolutePath());
                    args.add("-e");
                    args.add("(import (geiser emacs))");
                }

                // Pass source roots and project root for M-. support.
                List<File> sourceRoots = minecraftProvider.sourceRoots(project);
                if (!sourceRoots.isEmpty()) {
                    args.add("-Dkawa.source.path=" + joinPaths(sourceRoots));
                }
                args.add("-Dkawa.project.root=" + project.getProjectDir().getAbsolutePath());

                args.add("--server");
                args.add(String.valueOf(standalonePort));

                task.setArgs(args);
            });
        }

        // ---- kawaDoctor task ----
        project.getTasks().register("kawaDoctor", DefaultTask.class, task -> {
            task.setDescription("Print Kawa Forge diagnostics");
            task.setGroup("development");
            task.doLast(t -> runKawaDoctor(project, ext, minecraftProvider, schemeSourceDir));
        });

        // ---- kawaClasspathReport task ----
        project.getTasks().register("kawaClasspathReport", DefaultTask.class, task -> {
            task.setDescription("Print the Kawa REPL classpath");
            task.setGroup("development");
            task.doLast(t -> runClasspathReport(project, mainSourceSet, schemeOutputDir, minecraftProvider));
        });
    }

    // ------------------------------------------------------------------
    // kawaDoctor

    private void runKawaDoctor(Project project, KawaForgeExtension ext,
                               MinecraftClasspathProvider minecraftProvider,
                               File schemeSourceDir) {
        String sep = "\n  ";
        project.getLogger().lifecycle("");
        project.getLogger().lifecycle("=== Kawa Forge Diagnostics ===");
        project.getLogger().lifecycle("");

        // Project
        project.getLogger().lifecycle("Project root: " + project.getProjectDir());

        // Kawa runtime
        project.getLogger().lifecycle("Kawa runtime config:");
        KawaForgeExtension.KawaRuntimeConfig rt = ext.getRuntime();
        project.getLogger().lifecycle("  guix:  " + rt.getGuix());
        project.getLogger().lifecycle("  local: " + rt.getLocal());
        project.getLogger().lifecycle("  maven: " + rt.getMaven());

        // Scheme sources
        project.getLogger().lifecycle("Scheme source dir: " + schemeSourceDir
            + " (exists=" + schemeSourceDir.exists() + ")");

        // Geiser-kawa
        File geiserDir = resolveGeiserKawaSchemeDir(project);
        project.getLogger().lifecycle("Geiser-kawa scheme dir: "
            + (geiserDir != null ? geiserDir : "NOT FOUND")
            + (geiserDir != null ? " (exists=" + geiserDir.exists() + ")" : ""));

        // REPL ports
        project.getLogger().lifecycle("Standalone REPL port: " + ext.getRepl().getStandalonePort());
        project.getLogger().lifecycle("In-game REPL port:    " + ext.getRepl().getPort());

        // Minecraft classpath provider
        project.getLogger().lifecycle("Minecraft provider:  " + minecraftProvider.getClass().getSimpleName());
        project.getLogger().lifecycle("Source roots:" + sep
            + String.join(sep,
                minecraftProvider.sourceRoots(project).stream()
                    .map(File::getAbsolutePath)
                    .toArray(String[]::new)));

        // Key class check
        project.getLogger().lifecycle("");
        project.getLogger().lifecycle("Key class checks:");
        String[] keyClasses = {
            "cpw.mods.fml.common.registry.GameRegistry",
            "net.minecraft.block.Block",
            "net.minecraft.init.Blocks",
            "net.minecraftforge.common.MinecraftForge",
        };
        for (String cls : keyClasses) {
            boolean found = checkClassOnFileSystem(project, minecraftProvider, cls);
            project.getLogger().lifecycle("  " + cls + " ... " + (found ? "FOUND" : "MISSING"));
        }

        // Commands
        project.getLogger().lifecycle("");
        project.getLogger().lifecycle("Quick start:");
        project.getLogger().lifecycle("  ./gradlew kawaRepl       # start standalone REPL");
        project.getLogger().lifecycle("  ./gradlew kawaDoctor     # this report");
        project.getLogger().lifecycle("  ./gradlew kawaClasspathReport");
        project.getLogger().lifecycle("");
        project.getLogger().lifecycle("Then in Emacs:");
        project.getLogger().lifecycle("  M-x geiser-kawa-connect");
        project.getLogger().lifecycle("  port: " + ext.getRepl().getStandalonePort());
        project.getLogger().lifecycle("");
    }

    private boolean checkClassOnFileSystem(Project project,
                                           MinecraftClasspathProvider provider,
                                           String className) {
        String relPath = className.replace('.', '/') + ".class";
        // Check provider source roots parent dirs
        for (File root : provider.sourceRoots(project)) {
            if (new File(root.getParentFile(), "classes/java/patchedMc/" + relPath).exists())
                return true;
            if (new File(root.getParentFile(), "classes/java/main/" + relPath).exists())
                return true;
        }
        // Check build dir
        if (new File(project.getBuildDir(), "classes/java/patchedMc/" + relPath).exists())
            return true;
        if (new File(project.getBuildDir(), "classes/java/main/" + relPath).exists())
            return true;
        return false;
    }

    // ------------------------------------------------------------------
    // kawaClasspathReport

    private void runClasspathReport(Project project, SourceSet mainSourceSet,
                                    org.gradle.api.provider.Provider<org.gradle.api.file.Directory> schemeOutputDir,
                                    MinecraftClasspathProvider minecraftProvider) {
        org.gradle.api.file.FileCollection classpath = mainSourceSet.getRuntimeClasspath()
            .plus(project.files(schemeOutputDir))
            .plus(minecraftProvider.replClasspath(project, mainSourceSet))
            .filter(File::exists);

        project.getLogger().lifecycle("Kawa REPL classpath (" + classpath.getFiles().size() + " entries):");
        for (File f : classpath) {
            String kind = f.isDirectory() ? "dir" : "jar";
            project.getLogger().lifecycle("  [" + kind + "] " + f);
        }
    }

    // ------------------------------------------------------------------
    // Helpers

    private String joinPaths(List<File> files) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < files.size(); i++) {
            if (i > 0) sb.append(File.pathSeparator);
            sb.append(files.get(i).getAbsolutePath());
        }
        return sb.toString();
    }

    private Object resolveKawaDependency(Project project, KawaForgeExtension.KawaRuntimeConfig rt) {
        // 1. Explicit local
        if (rt.getLocal() != null) {
            return project.files(rt.getLocal());
        }
        // 2. Guix
        if (rt.getGuix()) {
            File guixJar = resolveGuixKawaJar(project);
            if (guixJar != null) return project.files(guixJar);
            project.getLogger().warn("kawa-forge: guix runtime provider failed, falling back");
        }
        // 3. Explicit Maven
        if (rt.getMaven() != null) {
            return rt.getMaven();
        }
        // 4. Default: local libs/kawa.jar
        File defaultLocal = project.file("libs/kawa.jar");
        if (defaultLocal.exists()) {
            return project.files(defaultLocal);
        }
        // 5. Default: kawa-runtime Maven artifact
        addKawaRuntimeRepo(project);
        return "com.momosoftworks.kawa:kawa-runtime:3.1.1";
    }

    private void addKawaForgeMavenRepo(Project project) {
        String name = "Kawa Forge";
        boolean exists = project.getRepositories().stream().anyMatch(r -> name.equals(r.getName()));
        if (!exists) {
            project.getRepositories().maven(repo -> {
                repo.setName(name);
                repo.setUrl("https://raw.githubusercontent.com/Momo-Softworks/kawa-forge/maven/");
                repo.metadataSources(ms -> {
                    ms.mavenPom();
                    ms.artifact();
                });
            });
        }
    }

    private void addKawaRuntimeRepo(Project project) {
        String name = "Kawa Runtime";
        boolean exists = project.getRepositories().stream().anyMatch(r -> name.equals(r.getName()));
        if (!exists) {
            project.getRepositories().maven(repo -> {
                repo.setName(name);
                repo.setUrl("https://raw.githubusercontent.com/Momo-Softworks/kawa-runtime/main/");
                repo.metadataSources(ms -> ms.artifact());
            });
        }
    }

    /**
     * Resolve the geiser-kawa scheme source directory.
     * Tries Guix first, then local checkout, then project-local.
     */
    private File resolveGeiserKawaSchemeDir(Project project) {
        // 1. Guix: guix build geiser-kawa
        File guixDir = resolveGuixGeiserKawaDir(project);
        if (guixDir != null) return guixDir;

        // 2. Local checkouts
        File[] localCheckouts = new File[] {
            new File(System.getProperty("user.home"), "Projects/Geiser/geiser-kawa/src"),
            new File(System.getProperty("user.home"), "Projects/geiser-kawa/src")
        };
        for (File localCheckout : localCheckouts) {
            if (new File(localCheckout, "geiser/emacs.scm").exists()) {
                project.getLogger().lifecycle("  geiser-kawa scheme: " + localCheckout);
                return localCheckout;
            }
        }

        // 3. Project-local libs/geiser-kawa-scheme/
        File projectLocal = project.file("libs/geiser-kawa-scheme");
        if (new File(projectLocal, "geiser/emacs.scm").exists()) {
            project.getLogger().lifecycle("  geiser-kawa scheme: " + projectLocal);
            return projectLocal;
        }

        project.getLogger().warn("kawa-forge: geiser-kawa scheme sources not found; "
            + "REPL will start without completions/autodoc");
        return null;
    }

    private File resolveGuixGeiserKawaDir(Project project) {
        try {
            ProcessBuilder pb = new ProcessBuilder("guix", "build", "geiser-kawa",
                "-L", System.getProperty("user.home") + "/.config/guix/modules");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) output.append(line).append('\n');
            int exit = proc.waitFor();
            if (exit != 0) return null;

            // Find the store path in output
            String storePath = null;
            for (String l : output.toString().split("\n")) {
                String t = l.trim();
                if (t.startsWith("/gnu/store/")) storePath = t;
            }
            if (storePath == null) return null;

            // The scheme sources are at <store>/share/emacs/site-lisp/<pkg>/src/
            File siteLisp = new File(storePath, "share/emacs/site-lisp");
            File[] pkgDirs = siteLisp.listFiles(
                (dir, name) -> name.startsWith("geiser-kawa-"));
            if (pkgDirs == null || pkgDirs.length == 0) return null;

            File srcDir = new File(pkgDirs[0], "src");
            if (new File(srcDir, "geiser/emacs.scm").exists()) {
                project.getLogger().lifecycle("  geiser-kawa scheme (guix): " + srcDir);
                return srcDir;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private File resolveGuixKawaJar(Project project) {
        try {
            ProcessBuilder pb = new ProcessBuilder("guix", "build", "kawa");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) output.append(line).append('\n');
            int exit = proc.waitFor();
            if (exit != 0) {
                project.getLogger().warn("kawa-forge: guix build kawa failed: " + output);
                return null;
            }
            String storePath = output.toString().trim();
            // Take the last /gnu/store/... line
            for (String l : output.toString().split("\n")) {
                String t = l.trim();
                if (t.startsWith("/gnu/store/")) storePath = t;
            }
            File jar = new File(storePath, "share/kawa/lib/kawa.jar");
            if (jar.exists()) {
                project.getLogger().lifecycle("  Kawa resolved via Guix: " + jar);
                return jar;
            }
            project.getLogger().warn("kawa-forge: kawa.jar not found at " + jar);
            return null;
        } catch (Exception e) {
            project.getLogger().warn("kawa-forge: guix resolution exception: " + e.getMessage());
            return null;
        }
    }

    private static boolean hasScmFiles(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return false;
        for (File f : files) {
            if (f.isDirectory()) { if (hasScmFiles(f)) return true; }
            else if (f.getName().endsWith(".scm")) return true;
        }
        return false;
    }
}
