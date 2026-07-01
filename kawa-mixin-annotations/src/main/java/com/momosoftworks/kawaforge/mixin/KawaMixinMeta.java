package com.momosoftworks.kawaforge.mixin;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Class-level carrier annotation for Kawa-authored Mixin classes.
 *
 * <p>The payload is an s-expression sequence of annotation forms as defined in
 * {@code docs/mixin-payload-spec.md} (v0). The {@code processKawaMixins} Gradle
 * task parses this payload, emits the real JVM annotations it describes into
 * the compiled class file, and strips this carrier. It never exists at runtime.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface KawaMixinMeta {
    /** Payload in the v0 carrier grammar, e.g. {@code (@ Mixin (targets "a.B"))}. */
    String value();
}
