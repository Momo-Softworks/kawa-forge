package com.momosoftworks.kawaforge.mixin.meta;

import java.util.LinkedHashMap;
import java.util.Objects;

public final class VAnnotation extends AnnValue {
    public final String typeName;
    public final LinkedHashMap<String, AnnValue> members;
    public final Boolean visible;

    public VAnnotation(String typeName, LinkedHashMap<String, AnnValue> members, Boolean visible) {
        this.typeName = typeName;
        this.members = members;
        this.visible = visible;
    }

    @Override
    public String toString() {
        return "VAnnotation{" + "typeName=" + typeName + ", members=" + members + ", visible=" + visible + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VAnnotation that = (VAnnotation) o;
        return Objects.equals(typeName, that.typeName) && 
               Objects.equals(members, that.members) && 
               Objects.equals(visible, that.visible);
    }

    @Override
    public int hashCode() {
        return Objects.hash(typeName, members, visible);
    }
}
