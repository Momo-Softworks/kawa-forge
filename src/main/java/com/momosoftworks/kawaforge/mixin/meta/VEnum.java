package com.momosoftworks.kawaforge.mixin.meta;

import java.util.Objects;

public final class VEnum extends AnnValue {
    public final String typeName;
    public final String constant;

    public VEnum(String typeName, String constant) {
        this.typeName = typeName;
        this.constant = constant;
    }

    @Override
    public String toString() {
        return "VEnum{" + "typeName=" + typeName + ", constant=" + constant + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VEnum vEnum = (VEnum) o;
        return Objects.equals(typeName, vEnum.typeName) && Objects.equals(constant, vEnum.constant);
    }

    @Override
    public int hashCode() {
        return Objects.hash(typeName, constant);
    }
}
