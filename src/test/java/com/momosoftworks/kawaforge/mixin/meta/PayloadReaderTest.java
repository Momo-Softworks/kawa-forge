package com.momosoftworks.kawaforge.mixin.meta;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PayloadReaderTest {

    @Test
    void testWorkedExample1() {
        String payload = "(@ Inject\n" +
                        "   (method \"startGame\")\n" +
                        "   (at (@ At (value \"HEAD\")))\n" +
                        "   (cancellable #t)\n" +
                        "   (require 1))";
        
        List<VAnnotation> result = PayloadReader.parse(payload);
        assertEquals(1, result.size());
        
        VAnnotation inject = result.get(0);
        assertEquals("Inject", inject.typeName);
        
        assertEquals(new VPrim("startGame"), inject.members.get("method"));
        
        VAnnotation atAnn = (VAnnotation) inject.members.get("at");
        assertEquals("At", atAnn.typeName);
        assertEquals(new VPrim("HEAD"), atAnn.members.get("value"));
        
        assertEquals(new VPrim(Boolean.TRUE), inject.members.get("cancellable"));
        assertEquals(new VPrim(1L), inject.members.get("require"));
    }

    @Test
    void testWorkedExample2() {
        String payload = "(@ Inject\n" +
                        "   (method \"func_71384_a\")\n" +
                        "   (at (@ At (value \"INVOKE\")\n" +
                        "            (target \"Lnet/minecraft/client/Minecraft;func_71357_I()V\")\n" +
                        "            (shift (enum org.spongepowered.asm.mixin.injection.At$Shift AFTER))))\n" +
                        "   (remap #f))\n" +
                        "\n" +
                        "(@ Mixin (value (class net.minecraft.client.Minecraft)) (targets (array)))";
        
        List<VAnnotation> result = PayloadReader.parse(payload);
        assertEquals(2, result.size());
        
        VAnnotation inject = result.get(0);
        assertEquals("Inject", inject.typeName);
        VAnnotation atAnn = (VAnnotation) inject.members.get("at");
        assertEquals("At", atAnn.typeName);
        assertEquals(new VPrim("INVOKE"), atAnn.members.get("value"));
        assertEquals(new VPrim("Lnet/minecraft/client/Minecraft;func_71357_I()V"), atAnn.members.get("target"));
        assertEquals(new VEnum("org.spongepowered.asm.mixin.injection.At$Shift", "AFTER"), atAnn.members.get("shift"));
        assertEquals(new VPrim(Boolean.FALSE), inject.members.get("remap"));
        
        VAnnotation mixin = result.get(1);
        assertEquals("Mixin", mixin.typeName);
        assertEquals(new VClass("net.minecraft.client.Minecraft"), mixin.members.get("value"));
        assertEquals(new VArray(Collections.emptyList()), mixin.members.get("targets"));
    }

    @Test
    void testScalars() {
        String payload = "(@ Test (i -123) (f 1.23e4) (b #t) (c #\\a))";
        List<VAnnotation> result = PayloadReader.parse(payload);
        VAnnotation ann = result.get(0);
        assertEquals(new VPrim(-123L), ann.members.get("i"));
        assertEquals(new VPrim(12300.0), ann.members.get("f"));
        assertEquals(new VPrim(Boolean.TRUE), ann.members.get("b"));
        assertEquals(new VPrim('a'), ann.members.get("c"));
    }

    @Test
    void testStringEscapes() {
        String payload = "(@ Test (s \"hello \\\"world\\n\\t\\r\\\\-done\"))";
        List<VAnnotation> result = PayloadReader.parse(payload);
        assertEquals(new VPrim("hello \"world\n\t\r\\-done"), result.get(0).members.get("s"));
    }

    @Test
    void testImplicitArray() {
        String payload = "(@ Test (vals \"a\" \"b\" \"c\"))";
        List<VAnnotation> result = PayloadReader.parse(payload);
        VArray array = (VArray) result.get(0).members.get("vals");
        assertEquals(3, array.values.size());
        assertEquals(new VPrim("a"), array.values.get(0));
        assertEquals(new VPrim("b"), array.values.get(1));
        assertEquals(new VPrim("c"), array.values.get(2));
    }

    @Test
    void testMultipleForms() {
        String payload = "(@ A (m 1)) (@ B (m 2))";
        List<VAnnotation> result = PayloadReader.parse(payload);
        assertEquals(2, result.size());
        assertEquals("A", result.get(0).typeName);
        assertEquals("B", result.get(1).typeName);
    }

    @Test
    void testComments() {
        String payload = "(@ Test ; comment\n (m 1) ; another comment\n)";
        List<VAnnotation> result = PayloadReader.parse(payload);
        assertEquals(1, result.size());
        assertEquals(new VPrim(1L), result.get(0).members.get("m"));
    }

    @Test
    void testErrors() {
        assertThrows(PayloadException.class, () -> PayloadReader.parse(""));
        assertThrows(PayloadException.class, () -> PayloadReader.parse("   "));
        assertThrows(PayloadException.class, () -> PayloadReader.parse("(@ Test (m \"unterminated)"));
        assertThrows(PayloadException.class, () -> PayloadReader.parse("(@ Test (m \"bad\\escape\")"));
        assertThrows(PayloadException.class, () -> PayloadReader.parse("(@ Test (m 1)) garbage"));
        assertThrows(PayloadException.class, () -> PayloadReader.parse("(@ Test (m 1) (m 2))"));
        assertThrows(PayloadException.class, () -> PayloadReader.parse("(@ Test (class-wrong (class a) b))")); // Not valid syntax
        assertThrows(PayloadException.class, () -> PayloadReader.parse("(@ Test (m (class a b)))")); // Arity
    }

    @Test
    void testIntegerOverflow() {
        // 2^63 is too large for Long
        String payload = "(@ Test (m 9223372036854775808))"; 
        assertThrows(PayloadException.class, () -> PayloadReader.parse(payload));
    }
}
