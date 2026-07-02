package com.momosoftworks.kawaforge.mixin;

import com.momosoftworks.kawaforge.mixin.meta.*;
import org.objectweb.asm.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

public final class MixinClassProcessor {
    private final Normalizer normalizer;
    private final List<String> warnings = new ArrayList<>();
    private static final String CARRIER_CLASS = "Lcom/momosoftworks/kawaforge/mixin/KawaMixinMeta;";
    private static final String CARRIER_MEMBER = "Lcom/momosoftworks/kawaforge/mixin/KawaMemberMeta;";

    public MixinClassProcessor(List<Path> definitionClasspath) {
        AnnotationDefReader reader = new AnnotationDefReader(definitionClasspath);
        this.normalizer = new Normalizer(reader);
    }

    /**
     * Warnings collected by the most recent {@link #process} call (empty when
     * the class was carrier-free or clean). Currently: references to Kawa
     * pooled literals, which Sponge Mixin cannot merge safely.
     */
    public List<String> warnings() {
        return new ArrayList<>(warnings);
    }

    public byte[] process(byte[] classBytes) {
        warnings.clear();
        ClassReader cr = new ClassReader(classBytes);
        String className = cr.getClassName();
        ClassWriter cw = new ClassWriter(0);
        
        final boolean[] sawCarrier = {false};
        final List<String> literalRefs = new ArrayList<>();
        
        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (descriptor.equals(CARRIER_CLASS)) {
                    sawCarrier[0] = true;
                    return new CarrierAnnotationVisitor(this, visible, className, null, null);
                }
                return super.visitAnnotation(descriptor, visible);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String desc, boolean vis) {
                        if (desc.equals(CARRIER_MEMBER)) {
                            sawCarrier[0] = true;
                            return new CarrierAnnotationVisitor(this, vis, className, name, descriptor);
                        }
                        return super.visitAnnotation(desc, vis);
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDesc) {
                        // Kawa pools Scheme literals (e.g. (display "...")) as
                        // static Lit* fields of gnu.* types on the module
                        // class, initialized in its <clinit>. Sponge Mixin
                        // does not run that initialization chain reliably from
                        // merged handler code, so such references break at
                        // runtime. String literals passed to String-typed Java
                        // APIs (or wrapped in the DSL's (jstr "...")) compile
                        // to plain ldc constants and are safe.
                        if (opcode == Opcodes.GETSTATIC
                                && (fieldDesc.startsWith("Lgnu/") || fieldName.matches("Lit\\d+"))) {
                            literalRefs.add(className.replace('/', '.') + "." + name
                                + " references Kawa pooled literal " + owner.replace('/', '.')
                                + "." + fieldName + " (" + fieldDesc + ")");
                        }
                        super.visitFieldInsn(opcode, owner, fieldName, fieldDesc);
                    }
                };
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                FieldVisitor fv = super.visitField(access, name, descriptor, signature, value);
                return new FieldVisitor(Opcodes.ASM9, fv) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String desc, boolean vis) {
                        if (desc.equals(CARRIER_MEMBER)) {
                            sawCarrier[0] = true;
                            return new CarrierAnnotationVisitor(this, vis, className, name, descriptor);
                        }
                        return super.visitAnnotation(desc, vis);
                    }
                };
            }
        };

        cr.accept(cv, 0);
        if (sawCarrier[0] && !literalRefs.isEmpty()) {
            for (String ref : literalRefs) {
                warnings.add(ref + " — Kawa pooled literals are initialized in the module's <clinit>,"
                    + " which Sponge Mixin will not run from merged code. Pass string literals to"
                    + " String-typed Java APIs or wrap them with (jstr \"...\").");
            }
        }
        return sawCarrier[0] ? cw.toByteArray() : null;
    }

    private class CarrierAnnotationVisitor extends AnnotationVisitor {
        private final Object target; // ClassVisitor, MethodVisitor, or FieldVisitor
        private final boolean visible;
        private final String className;
        private final String memberName;
        private final String memberDesc;
        private String payload;

        CarrierAnnotationVisitor(Object target, boolean visible, String className, String memberName, String memberDesc) {
            super(Opcodes.ASM9);
            this.target = target;
            this.visible = visible;
            this.className = className;
            this.memberName = memberName;
            this.memberDesc = memberDesc;
        }

        @Override
        public void visit(String name, Object value) {
            if ("value".equals(name)) {
                this.payload = (String) value;
            }
        }

        @Override
        public void visitEnd() {
            String context = (memberName == null) 
                ? "class " + className 
                : "member " + memberName + " " + memberDesc + " in class " + className;

            try {
                List<VAnnotation> annotations = PayloadReader.parse(payload);
                for (VAnnotation va : annotations) {
                    VAnnotation normalized = normalizer.normalize(va);
                    if (target instanceof ClassVisitor) {
                        AnnotationEmitter.emitOnClass((ClassVisitor) target, normalized);
                    } else if (target instanceof MethodVisitor) {
                        AnnotationEmitter.emitOnMethod((MethodVisitor) target, normalized);
                    } else if (target instanceof FieldVisitor) {
                        AnnotationEmitter.emitOnField((FieldVisitor) target, normalized);
                    }
                }
            } catch (PayloadException | NormalizationException e) {
                throw new MixinProcessingException(
                    "Failed to process mixin carrier in " + context + ". Payload: " + payload, e);
            }
        }
    }
}
