package com.momosoftworks.kawaforge.mixin.meta.fixtures;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.SOURCE)
public @interface FixtureSource {
    String value();
}
