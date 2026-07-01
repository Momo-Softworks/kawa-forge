package com.momosoftworks.kawaforge.mixin.meta;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class AnnotationDefReader {
    private final List<Path> classpath;
    private final Map<String, AnnotationDef> cache = new HashMap<>();

    public AnnotationDefReader(List<Path> classpath) {
        this.classpath = classpath;
    }

    public AnnotationDef load(String binaryName) {
        if (cache.containsKey(binaryName)) {
            return cache.get(binaryName);
        }

        byte[] classBytes = findClass(binaryName);
        if (classBytes == null) {
            throw new NormalizationException("Type not found on classpath: " + binaryName + "\nSearched: " + classpath);
        }

        AnnotationDef def = parseClass(classBytes, binaryName);
        cache.put(binaryName, def);
        return def;
    }

    private byte[] findClass(String binaryName) {
        String internalName = binaryName.replace('.', '/');
        String fileName = internalName + ".class";

        for (Path path : classpath) {
            if (Files.isDirectory(path)) {
                Path classFile = path.resolve(fileName);
                if (Files.exists(classFile)) {
                    try {
                        return Files.readAllBytes(classFile);
                    } catch (IOException e) {
                        // ignore
                    }
                }
            } else if (path.toString().endsWith(".jar")) {
                try (JarFile jar = new JarFile(path.toFile())) {
                    JarEntry entry = jar.getJarEntry(fileName);
                    if (entry != null) {
                        try (InputStream is = jar.getInputStream(entry)) {
                            return is.readAllBytes();
                        }
                    }
                } catch (IOException e) {
                    // ignore
                }
            }
        }
        return null;
    }

    private AnnotationDef parseClass(byte[] bytes, String binaryName) {
        DefVisitor visitor = new DefVisitor();
        new ClassReader(bytes).accept(visitor, 0);

        if ((visitor.access & Opcodes.ACC_ANNOTATION) == 0) {
            throw new NormalizationException("Type is not an annotation: " + binaryName);
        }

        boolean runtimeVisible = false;
        if ("RUNTIME".equals(visitor.retentionValue)) {
            runtimeVisible = true;
        } else if ("SOURCE".equals(visitor.retentionValue)) {
            throw new NormalizationException("Annotation has SOURCE retention and cannot be emitted into bytecode: " + binaryName);
        }
        // null or "CLASS" -> invisible (JLS default retention is CLASS)

        return new AnnotationDef(
            binaryName,
            "L" + binaryName.replace('.', '/') + ";",
            runtimeVisible,
            visitor.members
        );
    }

    private static class DefVisitor extends ClassVisitor {
        int access;
        String retentionValue = null; // "RUNTIME" | "CLASS" | "SOURCE" | null (absent)
        final LinkedHashMap<String, MemberDef> members = new LinkedHashMap<>();

        DefVisitor() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            this.access = access;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            if ((access & (Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC)) == 0 && !"<clinit>".equals(name)) {
                members.put(name, new MemberDef(name, Type.getReturnType(descriptor)));
            }
            return null;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if ("Ljava/lang/annotation/Retention;".equals(descriptor)) {
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitEnum(String name, String enumDescriptor, String value) {
                        retentionValue = value;
                    }
                };
            }
            return super.visitAnnotation(descriptor, visible);
        }
    }
}
