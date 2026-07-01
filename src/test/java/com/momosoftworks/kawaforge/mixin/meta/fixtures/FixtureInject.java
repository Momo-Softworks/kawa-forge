package com.momosoftworks.kawaforge.mixin.meta.fixtures;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface FixtureInject {
    String[] method();
    FixtureAt[] at();
    boolean cancellable() default false;
    int require() default -1;
}
