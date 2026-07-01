package com.momosoftworks.kawaforge.mixin.meta;

import org.objectweb.asm.Type;

public final class MemberDef {
    public final String name;
    public final Type type;

    public MemberDef(String name, Type type) {
        this.name = name;
        this.type = type;
    }
}
