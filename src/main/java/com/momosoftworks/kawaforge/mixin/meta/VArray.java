package com.momosoftworks.kawaforge.mixin.meta;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class VArray extends AnnValue {
    public final List<AnnValue> values;

    public VArray(List<AnnValue> values) {
        this.values = Collections.unmodifiableList(values);
    }

    @Override
    public String toString() {
        return "VArray{" + "values=" + values + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VArray vArray = (VArray) o;
        return Objects.equals(values, vArray.values);
    }

    @Override
    public int hashCode() {
        return Objects.hash(values);
    }
}
