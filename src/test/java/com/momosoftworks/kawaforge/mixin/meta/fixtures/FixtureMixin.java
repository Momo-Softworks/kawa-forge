package com.momosoftworks.kawaforge.mixin.meta.fixtures;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.CLASS)
public @interface FixtureMixin {
    Class<?>[] value() default {};
    String[] targets() default {};
    int priority() default 1000;
}
