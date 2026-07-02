package com.momosoftworks.kawaforge.mixin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class MixinConfigGeneratorTest {

    @TempDir
    Path tempDir;

    @Test
    void testFindMixinClasses() throws IOException {
        createMixinClass("com.example.mixin.MixinA");
        createMixinClass("com.example.mixin.client.MixinB");
        createPlainClass("com.example.other.Plain");
        
        List<String> result = MixinConfigGenerator.findMixinClasses(tempDir);
        
        assertEquals(Arrays.asList("com.example.mixin.MixinA", "com.example.mixin.client.MixinB"), result);
    }

    private void createMixinClass(String className) throws IOException {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, className.replace('.', '/'), null, null, null);
        cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        cw.visitEnd();
        Path path = tempDir.resolve(className.replace('.', '/') + ".class");
        Files.createDirectories(path.getParent());
        Files.write(path, cw.toByteArray());
    }

    @Test
    void testRender() {
        List<String> mixins = Arrays.asList("com.example.mixin.MixinA", "com.example.mixin.client.MixinB");
        String json = MixinConfigGenerator.render("com.example.mixin", mixins, true, "0.7.11", "JAVA_8");
        
        assertTrue(json.contains("\"required\": true"));
        assertTrue(json.contains("\"minVersion\": \"0.7.11\""));
        assertTrue(json.contains("\"package\": \"com.example.mixin\""));
        assertTrue(json.contains("\"compatibilityLevel\": \"JAVA_8\""));
        assertTrue(json.contains("\"MixinA\""));
        assertTrue(json.contains("\"client.MixinB\""));
    }

    @Test
    void testRenderThrowsOnWrongPackage() {
        List<String> mixins = Arrays.asList("com.example.mixin.MixinA");
        assertThrows(MixinConfigGenerator.MixinProcessingException.class, () -> {
            MixinConfigGenerator.render("com.wrong", mixins, true, "0.7.11", "JAVA_8");
        });
    }

    @Test
    void testEmptyDir() throws IOException {
        Path empty = tempDir.resolve("empty");
        Files.createDirectories(empty);
        List<String> result = MixinConfigGenerator.findMixinClasses(empty);
        assertTrue(result.isEmpty());
    }

    private void createPlainClass(String className) throws IOException {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, className.replace('.', '/'), null, null, null);
        cw.visitEnd();
        Path path = tempDir.resolve(className.replace('.', '/') + ".class");
        Files.createDirectories(path.getParent());
        Files.write(path, cw.toByteArray());
    }
}
