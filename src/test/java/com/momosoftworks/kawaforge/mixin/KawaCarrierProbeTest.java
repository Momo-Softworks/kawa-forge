package com.momosoftworks.kawaforge.mixin;

import com.momosoftworks.kawaforge.mixin.meta.fixtures.FixtureMixin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end probe against the REAL Kawa compiler: a hand-written
 * define-simple-class with carrier annotations is AOT-compiled by Kawa 3.1.1,
 * then run through MixinClassProcessor, and the resulting bytecode is checked
 * for the materialized annotations. This validates the Phase 0 contract
 * (docs/mixin-payload-spec.md §2) against real Kawa output, not synthetic ASM.
 */
class KawaCarrierProbeTest {

    private static final String FIX = "com.momosoftworks.kawaforge.mixin.meta.fixtures.";

    @TempDir
    Path tmp;

    @Test
    void carriersSurviveRealKawaCompileAndProcessing() throws Exception {
        KawaTestHarness.assumeAvailable();

        Path annotationClasses = codeSource(KawaMixinMeta.class);
        Path fixtureClasses = codeSource(FixtureMixin.class);

        String src =
            "(define-simple-class ProbeMixin ()\n"
            + "  (@com.momosoftworks.kawaforge.mixin.KawaMixinMeta value:\n"
            + "    \"(@ " + FIX + "FixtureMixin (targets \\\"net.minecraft.client.Minecraft\\\"))\")\n"
            + "  ((handler) :: void\n"
            + "   (@com.momosoftworks.kawaforge.mixin.KawaMemberMeta value:\n"
            + "     \"(@ " + FIX + "FixtureInject (method \\\"startGame\\\")"
            + " (at (@ " + FIX + "FixtureAt (value \\\"HEAD\\\")))"
            + " (cancellable #t))\")\n"
            + "   #!void))\n";

        Path out = KawaTestHarness.compile(tmp, "probe.scm", src,
            Arrays.asList(annotationClasses));

        byte[] mixinClass = Files.readAllBytes(out.resolve("ProbeMixin.class"));
        MixinClassProcessor processor =
            new MixinClassProcessor(Arrays.asList(fixtureClasses));

        byte[] processed = processor.process(mixinClass);
        assertNotNull(processed, "carrier-bearing Kawa class must be transformed");

        Summary s = summarize(processed);

        // class level: FixtureMixin materialized (invisible), carrier gone
        assertTrue(s.classAnnotations.containsKey("L" + (FIX + "FixtureMixin").replace('.', '/') + ";"));
        assertEquals(Boolean.FALSE,
            s.classAnnotationVisibility.get("L" + (FIX + "FixtureMixin").replace('.', '/') + ";"));
        assertEquals(Arrays.asList("net.minecraft.client.Minecraft"),
            s.classAnnotations.get("L" + (FIX + "FixtureMixin").replace('.', '/') + ";").get("targets"));
        assertFalse(s.classAnnotations.containsKey("Lcom/momosoftworks/kawaforge/mixin/KawaMixinMeta;"),
            "class carrier must be stripped");

        // method level: FixtureInject materialized (visible) with array members + nested At
        Map<String, Object> inject =
            s.methodAnnotations.get("L" + (FIX + "FixtureInject").replace('.', '/') + ";");
        assertNotNull(inject, "handler must carry the materialized FixtureInject");
        assertEquals(Boolean.TRUE,
            s.methodAnnotationVisibility.get("L" + (FIX + "FixtureInject").replace('.', '/') + ";"));
        assertEquals(Arrays.asList("startGame"), inject.get("method"));
        assertEquals(Boolean.TRUE, inject.get("cancellable"));
        @SuppressWarnings("unchecked")
        List<Object> at = (List<Object>) inject.get("at");
        assertEquals(1, at.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> atValues = (Map<String, Object>) at.get(0);
        assertEquals("HEAD", atValues.get("value"));
        assertFalse(s.methodAnnotations.containsKey("Lcom/momosoftworks/kawaforge/mixin/KawaMemberMeta;"),
            "member carrier must be stripped");

        // the Kawa module class alongside has no carriers -> untouched
        byte[] moduleClass = Files.readAllBytes(out.resolve("probe.class"));
        assertNull(processor.process(moduleClass), "carrier-free Kawa module class must pass through");
    }

    private static Path codeSource(Class<?> cls) throws Exception {
        return Paths.get(cls.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    // ---- minimal read-back collector ----

    private static final class Summary {
        final Map<String, Map<String, Object>> classAnnotations = new HashMap<>();
        final Map<String, Boolean> classAnnotationVisibility = new HashMap<>();
        final Map<String, Map<String, Object>> methodAnnotations = new HashMap<>();
        final Map<String, Boolean> methodAnnotationVisibility = new HashMap<>();
    }

    private static Summary summarize(byte[] classBytes) {
        Summary s = new Summary();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                Map<String, Object> values = new HashMap<>();
                s.classAnnotations.put(desc, values);
                s.classAnnotationVisibility.put(desc, visible);
                return collector(values);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!"handler".equals(name)) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                        Map<String, Object> values = new HashMap<>();
                        s.methodAnnotations.put(desc, values);
                        s.methodAnnotationVisibility.put(desc, visible);
                        return collector(values);
                    }
                };
            }
        }, 0);
        return s;
    }

    /** Collects annotation members: scalars as-is, arrays as List, nested annotations as Map. */
    private static AnnotationVisitor collector(Map<String, Object> into) {
        return new AnnotationVisitor(Opcodes.ASM9) {
            @Override
            public void visit(String name, Object value) {
                into.put(name, value);
            }

            @Override
            public void visitEnum(String name, String enumDesc, String constant) {
                into.put(name, enumDesc + "." + constant);
            }

            @Override
            public AnnotationVisitor visitAnnotation(String name, String desc) {
                Map<String, Object> nested = new HashMap<>();
                into.put(name, nested);
                return collector(nested);
            }

            @Override
            public AnnotationVisitor visitArray(String name) {
                List<Object> values = new ArrayList<>();
                into.put(name, values);
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(String n, Object v) {
                        values.add(v);
                    }

                    @Override
                    public void visitEnum(String n, String ed, String c) {
                        values.add(ed + "." + c);
                    }

                    @Override
                    public AnnotationVisitor visitAnnotation(String n, String d) {
                        Map<String, Object> nested = new HashMap<>();
                        values.add(nested);
                        return collector(nested);
                    }
                };
            }
        };
    }
}
