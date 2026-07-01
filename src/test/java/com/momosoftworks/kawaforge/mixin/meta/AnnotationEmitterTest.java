package com.momosoftworks.kawaforge.mixin.meta;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.Type;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class AnnotationEmitterTest {

    private Normalizer getNormalizer() {
        Path cpPath = Paths.get(com.momosoftworks.kawaforge.mixin.meta.fixtures.FixtureAt.class
                .getProtectionDomain().getCodeSource().getLocation().getPath());
        AnnotationDefReader reader = new AnnotationDefReader(Collections.singletonList(cpPath));
        return new Normalizer(reader);
    }

    @Test
    public void testRoundTripClassFixtureMixin() {
        String payload = "(@ com.momosoftworks.kawaforge.mixin.meta.fixtures.FixtureMixin (targets \"net.minecraft.client.Minecraft\") (priority 1001))";
        VAnnotation input = getNormalizer().normalize(PayloadReader.parse(payload).get(0));
        
        VAnnotation output = roundTripClass(input);
        Assertions.assertEquals(input, output);
    }

    @Test
    public void testRoundTripMethodFixtureInject() {
        String payload = "(@ com.momosoftworks.kawaforge.mixin.meta.fixtures.FixtureInject (method \"startGame\") (at (@ com.momosoftworks.kawaforge.mixin.meta.fixtures.FixtureAt (value \"HEAD\") (shift (enum com.momosoftworks.kawaforge.mixin.meta.fixtures.FixtureShift AFTER)))) (cancellable #t))";
        VAnnotation input = getNormalizer().normalize(PayloadReader.parse(payload).get(0));
        
        VAnnotation output = roundTripMethod(input);
        Assertions.assertEquals(input, output);
    }

    @Test
    public void testRoundTripFieldFixtureMixin() {
        String payload = "(@ com.momosoftworks.kawaforge.mixin.meta.fixtures.FixtureMixin (targets \"net.minecraft.client.Minecraft\") (priority 1001))";
        VAnnotation input = getNormalizer().normalize(PayloadReader.parse(payload).get(0));
        
        VAnnotation output = roundTripField(input);
        Assertions.assertEquals(input, output);
    }

    @Test
    public void testClassLiteralMembers() {
        String payload = "(@ com.momosoftworks.kawaforge.mixin.meta.fixtures.FixtureMixin (value (class java.lang.String) (class int)))";
        VAnnotation input = getNormalizer().normalize(PayloadReader.parse(payload).get(0));
        
        VAnnotation output = roundTripClass(input);
        Assertions.assertEquals(input, output);
    }

    @Test
    public void testUnnormalizedThrows() {
        VAnnotation raw = new VAnnotation("Lsome/Type;", new LinkedHashMap<>(), null);
        Assertions.assertThrows(IllegalStateException.class, () -> AnnotationEmitter.emitOnClass(null, raw));
    }

    @Test
    public void testMultiElementArray() {
        String payload = "(@ com.momosoftworks.kawaforge.mixin.meta.fixtures.FixtureMixin (targets \"a.B\" \"c.D\"))";
        VAnnotation input = getNormalizer().normalize(PayloadReader.parse(payload).get(0));
        
        VAnnotation output = roundTripClass(input);
        Assertions.assertEquals(input, output);
    }

    private VAnnotation roundTripClass(VAnnotation ann) {
        ClassWriter cw = new ClassWriter(0);
        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                super.visit(version, access, name, signature, superName, interfaces);
            }
        };
        
        cv.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "TestClass", null, "java/lang/Object", null);
        AnnotationEmitter.emitOnClass(cv, ann);
        cv.visitEnd();
        
        byte[] bytes = cw.toByteArray();
        return readBackClass(bytes);
    }

    private VAnnotation roundTripMethod(VAnnotation ann) {
        ClassWriter cw = new ClassWriter(0);
        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (mv != null) {
                    AnnotationEmitter.emitOnMethod(mv, ann);
                }
                return mv;
            }
        };
        cv.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "TestClass", null, "java/lang/Object", null);
        cv.visitMethod(Opcodes.ACC_PUBLIC, "handler", "()V", null, null);
        cv.visitEnd();
        
        byte[] bytes = cw.toByteArray();
        return readBackMethod(bytes);
    }

    private VAnnotation roundTripField(VAnnotation ann) {
        ClassWriter cw = new ClassWriter(0);
        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                FieldVisitor fv = super.visitField(access, name, descriptor, signature, value);
                if (fv != null) {
                    AnnotationEmitter.emitOnField(fv, ann);
                }
                return fv;
            }
        };
        cv.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "TestClass", null, "java/lang/Object", null);
        cv.visitField(Opcodes.ACC_PUBLIC, "f", "Ljava/lang/String;", null, null);
        cv.visitEnd();
        
        byte[] bytes = cw.toByteArray();
        return readBackField(bytes);
    }

    private VAnnotation readBackClass(byte[] bytes) {
        final VAnnotation[] result = new VAnnotation[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                AnnotationCollector collector = new AnnotationCollector(desc, visible);
                result[0] = collector.get();
                return collector;
            }
        }, 0);
        return result[0];
    }

    private VAnnotation readBackMethod(byte[] bytes) {
        final VAnnotation[] result = new VAnnotation[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String adesc, boolean visible) {
                        AnnotationCollector collector = new AnnotationCollector(adesc, visible);
                        result[0] = collector.get();
                        return collector;
                    }
                };
            }
        }, 0);
        return result[0];
    }

    private VAnnotation readBackField(byte[] bytes) {
        final VAnnotation[] result = new VAnnotation[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public FieldVisitor visitField(int acc, String name, String desc, String sig, Object val) {
                return new FieldVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String adesc, boolean visible) {
                        AnnotationCollector collector = new AnnotationCollector(adesc, visible);
                        result[0] = collector.get();
                        return collector;
                    }
                };
            }
        }, 0);
        return result[0];
    }

    private static class AnnotationCollector extends AnnotationVisitor {
        private final String desc;
        private final Boolean visible; // null = nested annotation (no own visibility)
        private final LinkedHashMap<String, AnnValue> members = new LinkedHashMap<>();

        AnnotationCollector(String desc, Boolean visible) {
            super(Opcodes.ASM9);
            this.desc = desc;
            this.visible = visible;
        }

        VAnnotation get() {
            return new VAnnotation(desc, members, visible);
        }

        @Override
        public void visit(String name, Object value) {
            members.put(name, convertValue(value));
        }

        @Override
        public void visitEnum(String name, String enumDesc, String value) {
            members.put(name, new VEnum(enumDesc, value));
        }

        @Override
        public AnnotationVisitor visitAnnotation(String name, String annDesc) {
            AnnotationCollector nested = new AnnotationCollector(annDesc, null);
            members.put(name, nested.get());
            return nested;
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            final List<AnnValue> values = new ArrayList<>();
            AnnotationVisitor arrAv = new AnnotationVisitor(Opcodes.ASM9) {
                @Override
                public void visit(String n, Object v) {
                    values.add(convertValue(v));
                }
                @Override
                public void visitEnum(String n, String ed, String val) {
                    values.add(new VEnum(ed, val));
                }
                @Override
                public AnnotationVisitor visitAnnotation(String n, String ad) {
                    AnnotationCollector nested = new AnnotationCollector(ad, null);
                    values.add(nested.get());
                    return nested;
                }
            };
            members.put(name, new VArray(values));
            return arrAv;
        }
    }

    private static AnnValue convertValue(Object v) {
        if (v instanceof Type) {
            return new VClass(((Type) v).getDescriptor());
        } else {
            return new VPrim(v);
        }
    }
}
