package com.momosoftworks.kawaforge.mixin.meta;

import java.util.Objects;

public final class VClass extends AnnValue {
    public final String typeName;

    public VClass(String typeName) {
        this.typeName = typeName;
    }

    @Override
    public String toString() {
        return "VClass{" + "typeName=" + typeName + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VClass vClass = (VClass) o;
        return Objects.equals(typeName, vClass.typeName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(typeName);
    }
}
