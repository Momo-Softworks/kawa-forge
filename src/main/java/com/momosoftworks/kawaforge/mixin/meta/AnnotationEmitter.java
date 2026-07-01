package com.momosoftworks.kawaforge.mixin.meta;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

public final class AnnotationEmitter {
    private AnnotationEmitter() {}

    public static void emitOnClass(ClassVisitor cv, VAnnotation ann) {
        if (ann.visible == null) {
            throw new IllegalStateException("annotation tree is not normalized: " + ann.typeName);
        }
        AnnotationVisitor av = cv.visitAnnotation(ann.typeName, ann.visible);
        fill(av, ann);
        av.visitEnd();
    }

    public static void emitOnMethod(MethodVisitor mv, VAnnotation ann) {
        if (ann.visible == null) {
            throw new IllegalStateException("annotation tree is not normalized: " + ann.typeName);
        }
        AnnotationVisitor av = mv.visitAnnotation(ann.typeName, ann.visible);
        fill(av, ann);
        av.visitEnd();
    }

    public static void emitOnField(FieldVisitor fv, VAnnotation ann) {
        if (ann.visible == null) {
            throw new IllegalStateException("annotation tree is not normalized: " + ann.typeName);
        }
        AnnotationVisitor av = fv.visitAnnotation(ann.typeName, ann.visible);
        fill(av, ann);
        av.visitEnd();
    }

    private static void fill(AnnotationVisitor av, VAnnotation ann) {
        ann.members.forEach((name, value) -> {
            if (value instanceof VPrim) {
                av.visit(name, ((VPrim) value).value);
            } else if (value instanceof VClass) {
                av.visit(name, Type.getType(((VClass) value).typeName));
            } else if (value instanceof VEnum) {
                VEnum ve = (VEnum) value;
                av.visitEnum(name, ve.typeName, ve.constant);
            } else if (value instanceof VAnnotation) {
                VAnnotation va = (VAnnotation) value;
                AnnotationVisitor nested = av.visitAnnotation(name, va.typeName);
                fill(nested, va);
                nested.visitEnd();
            } else if (value instanceof VArray) {
                VArray varr = (VArray) value;
                AnnotationVisitor arr = av.visitArray(name);
                for (AnnValue element : varr.values) {
                    emitArrayElement(arr, element);
                }
                arr.visitEnd();
            }
        });
    }

    private static void emitArrayElement(AnnotationVisitor arr, AnnValue value) {
        if (value instanceof VPrim) {
            arr.visit(null, ((VPrim) value).value);
        } else if (value instanceof VClass) {
            arr.visit(null, Type.getType(((VClass) value).typeName));
        } else if (value instanceof VEnum) {
            VEnum ve = (VEnum) value;
            arr.visitEnum(null, ve.typeName, ve.constant);
        } else if (value instanceof VAnnotation) {
            VAnnotation va = (VAnnotation) value;
            AnnotationVisitor nested = arr.visitAnnotation(null, va.typeName);
            fill(nested, va);
            nested.visitEnd();
        } else if (value instanceof VArray) {
            throw new IllegalStateException("JVM annotations cannot nest bare arrays");
        }
    }
}
