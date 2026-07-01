package com.momosoftworks.kawaforge.mixin.meta.fixtures;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
public @interface FixtureScalars {
    char c();
    byte b();
    long l();
    float f();
}
