package com.momosoftworks.kawaforge.mixin.meta;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import com.momosoftworks.kawaforge.mixin.meta.fixtures.*;

public class NormalizerTest {
    private Normalizer normalizer;
    private AnnotationDefReader reader;

    @BeforeEach
    void setup() throws Exception {
        Path testClasses = Paths.get(FixtureAt.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        reader = new AnnotationDefReader(Collections.singletonList(testClasses));
        normalizer = new Normalizer(reader);
    }

    @Test
    void testAliasResolve() {
        assertEquals("org.spongepowered.asm.mixin.Mixin", Aliases.resolve("Mixin"));
    }

    @Test
    void testUnknownAlias() {
        assertThrows(NormalizationException.class, () -> Aliases.resolve("Unknown"));
    }

    @Test
    void testSingletonWrapping() {
        // (@ FixtureInject (method "startGame")) -> method is String[]
        VAnnotation raw = new VAnnotation("com.momosoftworks.kawaforge.mixin.meta.fixtures.FixtureInject", 
            new LinkedHashMap<>(Map.of("method", new VPrim("startGame"))), null);
        VAnnotation norm = normalizer.normalize(raw);
        
        assertTrue(norm.members.get("method") instanceof VArray);
        VArray array = (VArray) norm.members.get("method");
        assertEquals(1, array.values.size());
        assertEquals(new VPrim("startGame"), array.values.get(0));
    }

    @Test
    void testImplicitArray() {
        // (@ FixtureMixin (targets "a" "b"))
        List<AnnValue> vals = new ArrayList<>();
        vals.add(new VPrim("a"));
        vals.add(new VPrim("b"));
        VAnnotation raw = new VAnnotation("com.momosoftworks.kawaforge.mixin.meta.fixtures.FixtureMixin", 
            new LinkedHashMap<>(Map.of("targets", new VArray(vals))), null);
        VAnnotation norm = normalizer.normalize(raw);
        
        VArray array = (VArray) norm.members.get("targets");
        assertEquals(2, array.values.size());
    }

    @Test
    void testNestedAnnotationArray() {
        // (@ FixtureInject (at (@ FixtureAt (value "HEAD"))))
        VAnnotation nestedRaw = new VAnnotation("com.momosoftworks.kawaforge.mixin.meta.fixtures.FixtureAt", 
            new LinkedHashMap<>(Map.of("value", new VPrim("HEAD"))), null);
        
        VAnnotation raw = new VAnnotation("com.momosoftworks.kawaforge.mixin.meta.fixtures.FixtureInject", 
            new LinkedHashMap<>(Map.of("at", new VArray(Collections.singletonList(nestedRaw)))), null);
        
        VAnnotation norm = normalizer.normalize(raw);
        VArray atArray = (VArray) norm.members.get("at");
        VAnnotation nestedNorm = (VAnnotation) atArray.values.get(0);
        
        assertNull(nestedNorm.visible);
        assertEquals("Lcom/momosoftworks/kawaforge/mixin/meta/fixtures/FixtureAt;", nestedNorm.typeName);
    }

    @Test
    void testTopLevelVisibility() {
        // FixtureInject -> RUNTIME (true)
        VAnnotation rawInject = new VAnnotation("com.momosoftworks.kawaforge.mixin.meta.fixtures.FixtureInject", new LinkedHashMap<>(), null);
        assertTrue(normalizer.normalize(rawInject).visible);

        // FixtureMixin -> CLASS (false)
        VAnnotation rawMixin = new VAnnotation("com.momosoftworks.kawaforge.mixin.meta.fixtures.FixtureMixin", new LinkedHashMap<>(), null);
        assertFalse(normalizer.normalize(rawMixin).visible);

        // FixtureSource -> SOURCE (Exception)
        VAnnotation rawSource = new VAnnotation("com.momosoftworks.kawaforge.mixin.meta.fixtures.FixtureSource", new LinkedHashMap<>(), null);
        assertThrows(NormalizationException.class, () -> normalizer.normalize(rawSource));
    }

    @Test
    void testIntNarrowing() {
        // (require 1) -> VPrim(Integer 1)
        VAnnotation raw = new VAnnotation("com.momosoftworks.kawaforge.mixin.meta.fixtures.FixtureInject", 
            new LinkedHashMap<>(Map.of("require", new VPrim(1L))), null);
        VAnnotation norm = normalizer.normalize(raw);
        
        VPrim val = (VPrim) norm.members.get("require");
        assertEquals(Integer.valueOf(1), val.value);

        // Range error
        VAnnotation rawBad = new VAnnotation("com.momosoftworks.kawaforge.mixin.meta.fixtures.FixtureInject", 
            new LinkedHashMap<>(Map.of("require", new VPrim(99999999999L))), null);
        assertThrows(NormalizationException.class, () -> normalizer.normalize(rawBad));
    }

    @Test
    void testUnknownMember() {
        VAnnotation raw = new VAnnotation("com.momosoftworks.kawaforge.mixin.meta.fixtures.FixtureInject", 
            new LinkedHashMap<>(Map.of("unknown", new VPrim("foo"))), null);
        NormalizationException ex = assertThrows(NormalizationException.class, () -> normalizer.normalize(raw));
        assertTrue(ex.getMessage().contains("Valid members:"));
    }

    @Test
    void testEnumMember() {
        // Correct enum
        VAnnotation raw = new VAnnotation("com.momosoftworks.kawaforge.mixin.meta.fixtures.FixtureAt", 
            new LinkedHashMap<>(Map.of("shift", new VEnum("com.momosoftworks.kawaforge.mixin.meta.fixtures.FixtureShift", "AFTER"))), null);
        VAnnotation norm = normalizer.normalize(raw);
        VEnum enumVal = (VEnum) norm.members.get("shift");
        assertEquals("Lcom/momosoftworks/kawaforge/mixin/meta/fixtures/FixtureShift;", enumVal.typeName);
        assertEquals("AFTER", enumVal.constant);

        // Wrong enum type
        VAnnotation rawBad = new VAnnotation("com.momosoftworks.kawaforge.mixin.meta.fixtures.FixtureAt", 
            new LinkedHashMap<>(Map.of("shift", new VEnum("com.some.OtherEnum", "VAL"))), null);
        assertThrows(NormalizationException.class, () -> normalizer.normalize(rawBad));
    }

    @Test
    void testClassLiteral() {
        // Dotted name -> descriptor
        VAnnotation raw = new VAnnotation("com.momosoftworks.kawaforge.mixin.meta.fixtures.FixtureMixin", 
            new LinkedHashMap<>(Map.of("value", new VArray(Collections.singletonList(new VClass("java.lang.String"))))), null);
        VAnnotation norm = normalizer.normalize(raw);
        VClass cls = (VClass) ((VArray) norm.members.get("value")).values.get(0);
        assertEquals("Ljava/lang/String;", cls.typeName);

        // Primitive -> descriptor
        VAnnotation rawPrim = new VAnnotation("com.momosoftworks.kawaforge.mixin.meta.fixtures.FixtureMixin", 
            new LinkedHashMap<>(Map.of("value", new VArray(Collections.singletonList(new VClass("int"))))), null);
        VAnnotation normPrim = normalizer.normalize(rawPrim);
        VClass clsPrim = (VClass) ((VArray) normPrim.members.get("value")).values.get(0);
        assertEquals("I", clsPrim.typeName);
    }

    @Test
    void testTypeMismatches() {
        // String given where boolean expected
        VAnnotation raw = new VAnnotation("com.momosoftworks.kawaforge.mixin.meta.fixtures.FixtureInject", 
            new LinkedHashMap<>(Map.of("cancellable", new VPrim("yes"))), null);
        assertThrows(NormalizationException.class, () -> normalizer.normalize(raw));

        // VArray given for non-array member
        VAnnotation rawArr = new VAnnotation("com.momosoftworks.kawaforge.mixin.meta.fixtures.FixtureInject", 
            new LinkedHashMap<>(Map.of("require", new VArray(Collections.singletonList(new VPrim(1L))))), null);
        assertThrows(NormalizationException.class, () -> normalizer.normalize(rawArr));
    }

    @Test
    void testUnresolvableAnnotation() {
        VAnnotation raw = new VAnnotation("com.missing.Annotation", new LinkedHashMap<>(), null);
        assertThrows(NormalizationException.class, () -> normalizer.normalize(raw));
    }
}
