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
                task.getMainClass().set("kawa.repl");
                task.args("--server", String.valueOf(standalonePort));
                task.setStandardInput(System.in);

                task.doFirst(spec -> {
                    org.gradle.api.file.FileCollection cp = mainSourceSet.getRuntimeClasspath()
                        .plus(project.files(schemeOutputDir));
                    // Try to add geiser-kawa runtime for completions.
                    try {
                        project.getDependencies().add("kawaRuntime",
                            "com.momosoftworks.kawa:geiser-kawa-runtime:0.1.0");
                        cp = cp.plus(project.getConfigurations().getByName("kawaRuntime"));
                        task.getMainClass().set("kawageiser.StartKawaWithGeiserSupport");
                    } catch (Exception ignored) {
                        // geiser not available — bare REPL without completions.
                    }
                    ((org.gradle.api.tasks.JavaExec) spec).setClasspath(cp);
                });
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
