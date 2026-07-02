package com.momosoftworks.kawaforge.mixin;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Synthesizes minimal stand-ins for the Sponge Mixin annotation classes
 * (matching names, member types, and retention) so harness tests can run the
 * normalizer against the DSL's real aliases without a Sponge dependency.
 */
final class SpongeStubs {

    private SpongeStubs() {
    }

    /** Idempotently writes the stubs under {@code dir} and returns it. */
    static synchronized Path ensure(Path dir) throws IOException {
        if (!Files.exists(dir.resolve("org/spongepowered/asm/mixin/Mixin.class"))) {
            writeAnnotation(dir, "org/spongepowered/asm/mixin/Mixin", "CLASS", new String[][] {
                {"targets", "()[Ljava/lang/String;"},
                {"value", "()[Ljava/lang/Class;"},
                {"priority", "()I"},
                {"remap", "()Z"},
            });
            writeAnnotation(dir, "org/spongepowered/asm/mixin/injection/At", "RUNTIME", new String[][] {
                {"value", "()Ljava/lang/String;"},
                {"target", "()Ljava/lang/String;"},
                {"ordinal", "()I"},
            });
            writeAnnotation(dir, "org/spongepowered/asm/mixin/injection/Inject", "RUNTIME", new String[][] {
                {"method", "()[Ljava/lang/String;"},
                {"at", "()[Lorg/spongepowered/asm/mixin/injection/At;"},
                {"cancellable", "()Z"},
                {"require", "()I"},
                {"remap", "()Z"},
            });
            writeAnnotation(dir, "org/spongepowered/asm/mixin/Shadow", "CLASS", new String[][] {});
            writeAnnotation(dir, "org/spongepowered/asm/mixin/Unique", "CLASS", new String[][] {});
        }
        return dir;
    }

    private static void writeAnnotation(Path root, String internalName, String retention,
                                        String[][] members) throws IOException {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_ANNOTATION | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
            internalName, null, "java/lang/Object",
            new String[] {"java/lang/annotation/Annotation"});
        AnnotationVisitor av = cw.visitAnnotation("Ljava/lang/annotation/Retention;", true);
        av.visitEnum("value", "Ljava/lang/annotation/RetentionPolicy;", retention);
        av.visitEnd();
        for (String[] m : members) {
            cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, m[0], m[1], null, null)
              .visitEnd();
        }
        cw.visitEnd();
        Path file = root.resolve(internalName + ".class");
        Files.createDirectories(file.getParent());
        Files.write(file, cw.toByteArray());
    }
}
