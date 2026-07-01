package com.momosoftworks.kawaforge.mixin;

import com.momosoftworks.kawaforge.mixin.meta.fixtures.*;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import java.io.*;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

public class KawaMixinProcessTaskFunctionalTest {

    @TempDir
    java.nio.file.Path projectDir;

    private byte[] synthesizeClass(String internalName, boolean hasClassCarrier, String classPayload,
                                    String methodName, boolean hasMethodCarrier, String methodPayload) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);

        if (hasClassCarrier) {
            AnnotationVisitor av = cw.visitAnnotation("Lcom/momosoftworks/kawaforge/mixin/KawaMixinMeta;", false);
            av.visit("value", classPayload);
            av.visitEnd();
        }

        if (methodName != null) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC, methodName, "()V", null, null);
            if (hasMethodCarrier) {
                AnnotationVisitor av = mv.visitAnnotation("Lcom/momosoftworks/kawaforge/mixin/KawaMemberMeta;", false);
                av.visit("value", methodPayload);
                av.visitEnd();
            }
            mv.visitCode();
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        cw.visitEnd();
        return cw.toByteArray();
    }

    private String generateBuildGradle() {
        String classpath = System.getProperty("java.class.path");
        String separator = java.io.File.pathSeparator;
        String[] entries = classpath.split(separator);
        
        List<String> selected = new ArrayList<>();
        for (String entry : entries) {
            if (entry.contains("classes/java/main") && entry.contains("kawa-forge-gradle")) {
                selected.add(entry);
            } else if (entry.toLowerCase().endsWith(".jar") && entry.contains("asm")) {
                selected.add(entry);
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("buildscript {\n");
        sb.append("    dependencies {\n");
        sb.append("        classpath files(");
        String joined = selected.stream()
                .map(s -> "'" + s.replace("\\", "\\\\") + "'")
                .collect(Collectors.joining(", "));
        sb.append(joined).append(")\n");
        sb.append("    }\n");
        sb.append("}\n\n");
        sb.append("apply plugin: 'base'\n\n");
        sb.append("tasks.register('processKawaMixins', com.momosoftworks.kawaforge.mixin.KawaMixinProcessTask) {\n");
        sb.append("    inputClassesDir = file('kawa-classes')\n");
        sb.append("    outputClassesDir = file('build/processed')\n");
        sb.append("    definitionClasspath.from(file('defs'))\n");
        sb.append("}\n");
        return sb.toString();
    }

    @Test
    public void testProcessKawaMixins() throws IOException, URISyntaxException {
        // 1. Setup files
        Files.writeString(projectDir.resolve("settings.gradle"), "rootProject.name = 'fixture'");
        Files.writeString(projectDir.resolve("build.gradle"), generateBuildGradle());

        Path kawaClasses = projectDir.resolve("kawa-classes");
        Path testPkg = kawaClasses.resolve("test");
        Files.createDirectories(testPkg);

        String classPayload = "(@ com.momosoftworks.kawaforge.mixin.meta.fixtures.FixtureMixin (targets \"net.minecraft.client.Minecraft\"))";
        String methodPayload = "(@ com.momosoftworks.kawaforge.mixin.meta.fixtures.FixtureInject (method \"startGame\") (at (@ com.momosoftworks.kawaforge.mixin.meta.fixtures.FixtureAt (value \"HEAD\"))) (cancellable #t))";
        
        byte[] generatedBytes = synthesizeClass("test/GeneratedMixin", true, classPayload, "handler", true, methodPayload);
        Files.write(testPkg.resolve("GeneratedMixin.class"), generatedBytes);

        byte[] plainBytes = synthesizeClass("test/Plain", false, null, null, false, null);
        Files.write(testPkg.resolve("Plain.class"), plainBytes);

        Path defs = projectDir.resolve("defs");
        Path fixtureSourceDir = Paths.get(FixtureMixin.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        
        // Copy fixture annotations
        String[] fixtures = {"FixtureMixin", "FixtureInject", "FixtureAt", "FixtureShift"};
        for (String f : fixtures) {
            Path src = fixtureSourceDir.resolve("com/momosoftworks/kawaforge/mixin/meta/fixtures/" + f + ".class");
            Path dst = defs.resolve("com/momosoftworks/kawaforge/mixin/meta/fixtures/" + f + ".class");
            Files.createDirectories(dst.getParent());
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        }

        // 2. Run Task
        BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments("processKawaMixins", "--stacktrace")
                .build();
        
        Assertions.assertTrue(result.getOutput().contains("Kawa Mixin: scanned 2 classes, transformed 1"));

        // 3. Verify Outputs
        Path processedDir = projectDir.resolve("build/processed");
        Path processedMixin = processedDir.resolve("test/GeneratedMixin.class");
        Path processedPlain = processedDir.resolve("test/Plain.class");

        Assertions.assertTrue(Files.exists(processedMixin));
        Assertions.assertTrue(Files.exists(processedPlain));
        Assertions.assertArrayEquals(plainBytes, Files.readAllBytes(processedPlain), "Plain class should be copied verbatim");

        byte[] resultBytes = Files.readAllBytes(processedMixin);
        ClassReader cr = new ClassReader(resultBytes);
        
        final boolean[] foundFixtureMixin = {false};
        final boolean[] foundCarrierClass = {false};
        final boolean[] foundKawaMemberMeta = {false};
        
        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, new ClassWriter(0)) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (descriptor.equals("Lcom/momosoftworks/kawaforge/mixin/meta/fixtures/FixtureMixin;")) {
                    foundFixtureMixin[0] = true;
                } else if (descriptor.equals("Lcom/momosoftworks/kawaforge/mixin/KawaMixinMeta;")) {
                    foundCarrierClass[0] = true;
                }
                return super.visitAnnotation(descriptor, visible);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if ("handler".equals(name)) {
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public AnnotationVisitor visitAnnotation(String desc, boolean vis) {
                            if (desc.equals("Lcom/momosoftworks/kawaforge/mixin/KawaMemberMeta;")) {
                                foundKawaMemberMeta[0] = true;
                            }
                            return super.visitAnnotation(desc, vis);
                        }
                    };
                }
                return mv;
            }
        };
        cr.accept(cv, 0);
        
        Assertions.assertTrue(foundFixtureMixin[0], "Should have FixtureMixin");
        Assertions.assertFalse(foundCarrierClass[0], "Should NOT have KawaMixinMeta");
        Assertions.assertFalse(foundKawaMemberMeta[0], "Should NOT have KawaMemberMeta");

        String resultStr = new String(resultBytes);
        Assertions.assertTrue(resultStr.contains("Lcom/momosoftworks/kawaforge/mixin/meta/fixtures/FixtureInject;"));
        Assertions.assertTrue(resultStr.contains("Lcom/momosoftworks/kawaforge/mixin/meta/fixtures/FixtureAt;"));

        // 4. Verify UP-TO-DATE
        BuildResult resultUpToDate = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments("processKawaMixins", "--stacktrace")
                .build();
        Assertions.assertTrue(resultUpToDate.getOutput().contains("UP-TO-DATE"));
    }

    @Test
    public void testProcessKawaMixinsFailsOnGarbage() throws IOException, URISyntaxException {
        Files.writeString(projectDir.resolve("settings.gradle"), "rootProject.name = 'fixture-fail'");
        Files.writeString(projectDir.resolve("build.gradle"), generateBuildGradle());

        Path kawaClasses = projectDir.resolve("kawa-classes");
        Path testPkg = kawaClasses.resolve("test");
        Files.createDirectories(testPkg);

        byte[] garbageBytes = synthesizeClass("test/GeneratedMixin", true, "(@", "handler", true, "(@");
        Files.write(testPkg.resolve("GeneratedMixin.class"), garbageBytes);

        Path defs = projectDir.resolve("defs");
        Path fixtureSourceDir = Paths.get(FixtureMixin.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String[] fixtures = {"FixtureMixin", "FixtureInject", "FixtureAt", "FixtureShift"};
        for (String f : fixtures) {
            Path src = fixtureSourceDir.resolve("com/momosoftworks/kawaforge/mixin/meta/fixtures/" + f + ".class");
            Path dst = defs.resolve("com/momosoftworks/kawaforge/mixin/meta/fixtures/" + f + ".class");
            Files.createDirectories(dst.getParent());
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        }

        BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments("processKawaMixins", "--stacktrace")
                .buildAndFail();
        
        Assertions.assertTrue(result.getOutput().contains("GeneratedMixin"));
    }
}
