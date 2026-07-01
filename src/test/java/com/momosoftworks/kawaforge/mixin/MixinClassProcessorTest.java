package com.momosoftworks.kawaforge.mixin;

import com.momosoftworks.kawaforge.mixin.meta.fixtures.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.util.*;

public class MixinClassProcessorTest {

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

    @Test
    public void testSuccessfulProcessing() throws IOException, URISyntaxException {
        String className = "test/GeneratedMixin";
        String classPayload = "(@ com.momosoftworks.kawaforge.mixin.meta.fixtures.FixtureMixin (targets \"net.minecraft.client.Minecraft\"))";
        String methodPayload = "(@ com.momosoftworks.kawaforge.mixin.meta.fixtures.FixtureInject (method \"startGame\") (at (@ com.momosoftworks.kawaforge.mixin.meta.fixtures.FixtureAt (value \"HEAD\"))) (cancellable #t))";

        byte[] bytes = synthesizeClass(className, true, classPayload, "handler", true, methodPayload);
        
        // Build processor with test classes on classpath
        Path testClassesDir = Paths.get(FixtureMixin.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        MixinClassProcessor processor = new MixinClassProcessor(Collections.singletonList(testClassesDir));
        
        byte[] result = processor.process(bytes);
        Assertions.assertNotNull(result);

        ClassReader cr = new ClassReader(result);
        
        // Verify class annotation using a ClassVisitor
        final boolean[] foundFixtureMixin = {false};
        final boolean[] foundCarrierClass = {false};
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
        };
        cr.accept(cv, 0);
        Assertions.assertTrue(foundFixtureMixin[0]);
        Assertions.assertFalse(foundCarrierClass[0]);

        // Verify method annotation by scanning the bytes for the descriptor
        String resultStr = new String(result);
        Assertions.assertTrue(resultStr.contains("Lcom/momosoftworks/kawaforge/mixin/meta/fixtures/FixtureInject;"));
        Assertions.assertTrue(resultStr.contains("Lcom/momosoftworks/kawaforge/mixin/meta/fixtures/FixtureAt;"));
        Assertions.assertFalse(resultStr.contains("Lcom/momosoftworks/kawaforge/mixin/KawaMemberMeta;"));
    }

    @Test
    public void testNoCarriersReturnsNull() throws IOException, URISyntaxException {
        byte[] bytes = synthesizeClass("test/NoCarrier", false, null, "foo", false, null);
        Path testClassesDir = Paths.get(FixtureMixin.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        MixinClassProcessor processor = new MixinClassProcessor(Collections.singletonList(testClassesDir));
        
        byte[] result = processor.process(bytes);
        Assertions.assertNull(result);
    }

    @Test
    public void testGarbagePayloadThrowsException() throws IOException, URISyntaxException {
        String className = "test/Garbage";
        byte[] bytes = synthesizeClass(className, true, "(@", "foo", true, "(@");
        
        Path testClassesDir = Paths.get(FixtureMixin.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        MixinClassProcessor processor = new MixinClassProcessor(Collections.singletonList(testClassesDir));
        
        MixinProcessingException ex = Assertions.assertThrows(MixinProcessingException.class, 
            () -> processor.process(bytes));
        
        Assertions.assertTrue(ex.getMessage().contains(className));
        Assertions.assertTrue(ex.getMessage().contains("(@"));
    }

    @Test
    public void testMultipleClassAnnotations() throws IOException, URISyntaxException {
        String className = "test/MultiAnn";
        String classPayload = "(@ com.momosoftworks.kawaforge.mixin.meta.fixtures.FixtureMixin (targets \"a.B\")) (@ com.momosoftworks.kawaforge.mixin.meta.fixtures.FixtureScalars (c #\\x))";
        
        byte[] bytes = synthesizeClass(className, true, classPayload, null, false, null);
        Path testClassesDir = Paths.get(FixtureMixin.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        MixinClassProcessor processor = new MixinClassProcessor(Collections.singletonList(testClassesDir));
        
        byte[] result = processor.process(bytes);
        ClassReader cr = new ClassReader(result);
        
        final boolean[] foundMixin = {false};
        final boolean[] foundScalars = {false};
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                if (desc.equals("Lcom/momosoftworks/kawaforge/mixin/meta/fixtures/FixtureMixin;")) foundMixin[0] = true;
                if (desc.equals("Lcom/momosoftworks/kawaforge/mixin/meta/fixtures/FixtureScalars;")) foundScalars[0] = true;
                return super.visitAnnotation(desc, visible);
            }
        }, 0);
        
        Assertions.assertTrue(foundMixin[0]);
        Assertions.assertTrue(foundScalars[0]);
    }

    @Test
    public void testFieldKawaMemberMeta() throws IOException, URISyntaxException {
        String className = "test/FieldMeta";
        String methodPayload = "(@ com.momosoftworks.kawaforge.mixin.meta.fixtures.FixtureMixin (targets \"a.B\"))";
        
        // synthesizeClass currently only handles methods. I need to add field support to it or manually build the class.
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null);
        
        FieldVisitor fv = cw.visitField(Opcodes.ACC_PUBLIC, "metaField", "Lcom/momosoftworks/kawaforge/mixin/KawaMemberMeta;", null, null);
        AnnotationVisitor av = fv.visitAnnotation("Lcom/momosoftworks/kawaforge/mixin/KawaMemberMeta;", false);
        av.visit("value", methodPayload);
        av.visitEnd();
        fv.visitEnd();
        cw.visitEnd();
        
        byte[] bytes = cw.toByteArray();
        Path testClassesDir = Paths.get(FixtureMixin.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        MixinClassProcessor processor = new MixinClassProcessor(Collections.singletonList(testClassesDir));
        
        byte[] result = processor.process(bytes);
        ClassReader cr = new ClassReader(result);
        
        final boolean[] foundMixin = {false};
        final boolean[] foundMeta = {false};
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                return new FieldVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String adesc, boolean visible) {
                        if (adesc.equals("Lcom/momosoftworks/kawaforge/mixin/meta/fixtures/FixtureMixin;")) foundMixin[0] = true;
                        if (adesc.equals("Lcom/momosoftworks/kawaforge/mixin/KawaMemberMeta;")) foundMeta[0] = true;
                        return super.visitAnnotation(adesc, visible);
                    }
                };
            }
        }, 0);
        
        Assertions.assertTrue(foundMixin[0], "Should have the real annotation");
        Assertions.assertFalse(foundMeta[0], "Should NOT have the carrier");
    }
}
