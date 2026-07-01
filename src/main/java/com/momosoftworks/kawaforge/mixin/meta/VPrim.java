package com.momosoftworks.kawaforge.mixin.meta;

import java.util.Objects;

public final class VPrim extends AnnValue {
    public final Object value;

    public VPrim(Object value) {
        // Parse time produces String/Long/Double/Boolean/Character; the
        // normalizer re-boxes numerics to the exact type the annotation
        // member declares (Byte/Short/Integer/Long/Float/Double), which is
        // also what ASM's AnnotationVisitor.visit(...) expects.
        if (!(value instanceof String || value instanceof Byte || value instanceof Short
              || value instanceof Integer || value instanceof Long || value instanceof Float
              || value instanceof Double || value instanceof Boolean || value instanceof Character)) {
            throw new IllegalArgumentException(
                "VPrim value must be String, a boxed primitive, or Character; got: "
                + (value == null ? "null" : value.getClass().getName()));
        }
        this.value = value;
    }

    @Override
    public String toString() {
        return "VPrim{" + "value=" + value + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VPrim vPrim = (VPrim) o;
        return Objects.equals(value, vPrim.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }
}
