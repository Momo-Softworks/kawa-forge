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

public class KawaForgePlugin implements Plugin<Project> {

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

        // ---- Wire into main ----
        mainSourceSet.getOutput().dir(
            java.util.Collections.singletonMap("builtBy", compileKawa),
            schemeOutputDir);
        project.getTasks().getByName("compileJava").dependsOn(compileKawa);

        // ---- kawaRepl task ----
        int standalonePort = ext.getRepl().getStandalonePort();
        if (standalonePort > 0) {
            project.getTasks().register("kawaRepl", org.gradle.api.tasks.JavaExec.class, task -> {
                task.setDescription("Start a Kawa REPL with the mod's full classpath");
                task.setGroup("development");
                task.setStandardInput(System.in);
                task.setClasspath(project.getConfigurations().getByName("compileClasspath")
                    .plus(project.files(schemeOutputDir)));

                // Resolve geiser-kawa v2 scheme sources (pure Scheme, no Java middleware).
                File geiserSchemeDir = resolveGeiserKawaSchemeDir(project);

                task.getMainClass().set("kawa.repl");
                if (geiserSchemeDir != null) {
                    task.args("-Dkawa.import.path=" + geiserSchemeDir.getAbsolutePath(),
                              "-e", "(import (geiser emacs))",
                              "-s",
                              "--server", String.valueOf(standalonePort));
                } else {
                    task.args("--server", String.valueOf(standalonePort));
                }
            });
        }
    }

    // ------------------------------------------------------------------
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
     * Resolve the geiser-kawa v2 scheme source directory.
     * Tries Guix first, then local checkout, then project-local.
     */
    private File resolveGeiserKawaSchemeDir(Project project) {
        // 1. Guix: guix build geiser-kawa
        File guixDir = resolveGuixGeiserKawaDir(project);
        if (guixDir != null) return guixDir;

        // 2. Local checkout
        File localCheckout = new File(System.getProperty("user.home"),
            "Projects/geiser-kawa/src");
        if (new File(localCheckout, "geiser/emacs.scm").exists()) {
            project.getLogger().lifecycle("  geiser-kawa scheme: " + localCheckout);
            return localCheckout;
        }

        // 3. Project-local libs/geiser-kawa-scheme/
        File projectLocal = project.file("libs/geiser-kawa-scheme");
        if (new File(projectLocal, "geiser/emacs.scm").exists()) {
            project.getLogger().lifecycle("  geiser-kawa scheme: " + projectLocal);
            return projectLocal;
        }

        project.getLogger().warn("kawa-forge: geiser-kawa v2 scheme sources not found; "
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
