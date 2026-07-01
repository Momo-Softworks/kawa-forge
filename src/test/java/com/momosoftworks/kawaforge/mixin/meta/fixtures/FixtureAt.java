package com.momosoftworks.kawaforge.mixin.meta.fixtures;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface FixtureAt {
    String value();
    FixtureShift shift() default FixtureShift.NONE;
}
