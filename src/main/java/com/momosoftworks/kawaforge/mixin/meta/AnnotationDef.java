package com.momosoftworks.kawaforge.mixin.meta;

import java.util.LinkedHashMap;

public final class AnnotationDef {
    public final String binaryName;
    public final String descriptor;
    public final boolean runtimeVisible;
    public final LinkedHashMap<String, MemberDef> members;

    public AnnotationDef(String binaryName, String descriptor, boolean runtimeVisible, LinkedHashMap<String, MemberDef> members) {
        this.binaryName = binaryName;
        this.descriptor = descriptor;
        this.runtimeVisible = runtimeVisible;
        this.members = members;
    }
}
