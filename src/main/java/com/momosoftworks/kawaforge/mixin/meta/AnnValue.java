package com.momosoftworks.kawaforge.mixin.meta;

import java.util.Objects;

public abstract class AnnValue {
    AnnValue() {}

    @Override
    public abstract String toString();

    @Override
    public abstract boolean equals(Object o);

    @Override
    public abstract int hashCode();
}
