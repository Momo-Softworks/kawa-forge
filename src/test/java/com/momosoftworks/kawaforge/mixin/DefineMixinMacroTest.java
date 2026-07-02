package com.momosoftworks.kawaforge.mixin;

import com.momosoftworks.kawaforge.mixin.meta.PayloadReader;
import com.momosoftworks.kawaforge.mixin.meta.VAnnotation;
import com.momosoftworks.kawaforge.mixin.meta.VPrim;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Compiles a consumer fixture that uses (import (kawaforge mixin)) +
 * define-mixin with the REAL Kawa compiler, then asserts the expansion
 * attached the exact carrier payloads promised by docs/mixin-dsl-spec.md.
 *
 * <p>The DSL module is precompiled to a classes dir first — importing it from
 * source via kawa.import.path is known-broken for procedural macros (the
 * transformer runs in a degraded lazy-compile mode). The Gradle wiring must
 * use the same precompile-then-classpath approach.
 */
class DefineMixinMacroTest {

    private static final String CLASS_CARRIER = "Lcom/momosoftworks/kawaforge/mixin/KawaMixinMeta;";
    private static final String MEMBER_CARRIER = "Lcom/momosoftworks/kawaforge/mixin/KawaMemberMeta;";

    @TempDir
    Path tmp;

    @BeforeAll
    static void requireKawa() {
        KawaTestHarness.assumeAvailable();
    }

    @Test
    void defineMixinExpandsToSpecifiedCarriers() throws Exception {
        // 1. Precompile the DSL module exactly as the plugin will.
        Path dslWork = Files.createDirectories(tmp.resolve("dsl"));
        String dslSource = new String(
            Files.readAllBytes(Paths.get("src/main/resources/kawaforge/mixin.scm")),
            StandardCharsets.UTF_8);
        Path dslClasses = KawaTestHarness.compile(dslWork, "mixin.scm", dslSource,
            Arrays.asList());

        // 2. Compile the consumer fixture with annotations + DSL on the classpath.
        Path annotationClasses = codeSource(KawaMixinMeta.class);
        String consumer =
            "(import (kawaforge mixin))\n"
            + "(define-mixin MixinMinecraft\n"
            + "  (target \"net.minecraft.client.Minecraft\")\n"
            + "  (priority 1001)\n"
            + "  (inject onStartGame ((ci :: java.lang.Object))\n"
            + "    (method \"startGame\")\n"
            + "    (at \"HEAD\")\n"
            + "    (cancellable #t)\n"
            + "    (display \">>> KAWA MIXIN WORKS <<<\")\n"
            + "    (newline))\n"
            + "  (inject afterInit ((ci :: java.lang.Object))\n"
            + "    (method \"startGame\")\n"
            + "    (at (value \"INVOKE\")\n"
            + "        (target \"Lnet/minecraft/client/Minecraft;func_71357_I()V\")\n"
            + "        (shift AFTER))\n"
            + "    (remap #f)\n"
            + "    #!void)\n"
            + "  (shadow-field counter int)\n"
            + "  (unique (helper ((x :: int)) :: int)\n"
            + "    (* x 2)))\n";
        Path consumerWork = Files.createDirectories(tmp.resolve("consumer"));
        Path out = KawaTestHarness.compile(consumerWork, "consumer.scm", consumer,
            Arrays.asList(annotationClasses, dslClasses));

        // 3. Extract carriers and compare parsed payload trees against the spec.
        Carriers c = readCarriers(Files.readAllBytes(out.resolve("MixinMinecraft.class")));

        assertNotNull(c.classPayload, "class must carry KawaMixinMeta");
        assertEquals(
            ann("Mixin",
                m("targets", new VPrim("net.minecraft.client.Minecraft")),
                m("priority", new VPrim(Long.valueOf(1001)))),
            parseOne(c.classPayload));

        assertEquals(
            ann("Inject",
                m("method", new VPrim("startGame")),
                m("at", ann("At", m("value", new VPrim("HEAD")))),
                m("cancellable", new VPrim(Boolean.TRUE))),
            parseOne(c.memberPayloads.get("onStartGame")));

        assertEquals(
            ann("Inject",
                m("method", new VPrim("startGame")),
                m("at", ann("At",
                    m("value", new VPrim("INVOKE")),
                    m("target", new VPrim("Lnet/minecraft/client/Minecraft;func_71357_I()V")),
                    m("shift", new com.momosoftworks.kawaforge.mixin.meta.VEnum(
                        "org.spongepowered.asm.mixin.injection.At$Shift", "AFTER")))),
                m("remap", new VPrim(Boolean.FALSE))),
            parseOne(c.memberPayloads.get("afterInit")));

        assertEquals(ann("Shadow"), parseOne(c.memberPayloads.get("counter")));
        assertEquals(ann("Unique"), parseOne(c.memberPayloads.get("helper")));
    }

    // ---- helpers ----

    private static VAnnotation parseOne(String payload) {
        assertNotNull(payload, "expected a carrier payload");
        List<VAnnotation> anns = PayloadReader.parse(payload);
        assertEquals(1, anns.size(), "expected exactly one annotation form in: " + payload);
        return anns.get(0);
    }

    @SafeVarargs
    private static VAnnotation ann(String type, Map.Entry<String, com.momosoftworks.kawaforge.mixin.meta.AnnValue>... members) {
        LinkedHashMap<String, com.momosoftworks.kawaforge.mixin.meta.AnnValue> m = new LinkedHashMap<>();
        for (Map.Entry<String, com.momosoftworks.kawaforge.mixin.meta.AnnValue> e : members) {
            m.put(e.getKey(), e.getValue());
        }
        return new VAnnotation(type, m, null);
    }

    private static Map.Entry<String, com.momosoftworks.kawaforge.mixin.meta.AnnValue> m(
            String name, com.momosoftworks.kawaforge.mixin.meta.AnnValue v) {
        return new java.util.AbstractMap.SimpleEntry<>(name, v);
    }

    private static Path codeSource(Class<?> cls) throws Exception {
        return Paths.get(cls.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private static final class Carriers {
        String classPayload;
        final Map<String, String> memberPayloads = new HashMap<>(); // member name -> payload
    }

    private static Carriers readCarriers(byte[] classBytes) {
        Carriers c = new Carriers();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                if (CLASS_CARRIER.equals(desc)) {
                    return valueGrabber(v -> c.classPayload = v);
                }
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                        if (MEMBER_CARRIER.equals(desc)) {
                            return valueGrabber(v -> c.memberPayloads.put(name, v));
                        }
                        return null;
                    }
                };
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                                           String signature, Object value) {
                return new FieldVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                        if (MEMBER_CARRIER.equals(desc)) {
                            return valueGrabber(v -> c.memberPayloads.put(name, v));
                        }
                        return null;
                    }
                };
            }
        }, 0);
        return c;
    }

    private static AnnotationVisitor valueGrabber(java.util.function.Consumer<String> sink) {
        return new AnnotationVisitor(Opcodes.ASM9) {
            @Override
            public void visit(String name, Object value) {
                if ("value".equals(name)) {
                    sink.accept((String) value);
                }
            }
        };
    }
}
