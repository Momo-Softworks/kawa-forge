package com.momosoftworks.kawaforge.mixin;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Method/field-level carrier annotation for Kawa-authored Mixin members.
 *
 * <p>The payload is an s-expression sequence of annotation forms as defined in
 * {@code docs/mixin-payload-spec.md} (v0). The {@code processKawaMixins} Gradle
 * task parses this payload, emits the real JVM annotations it describes onto
 * the member, and strips this carrier. It never exists at runtime.
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.FIELD})
public @interface KawaMemberMeta {
    /** Payload in the v0 carrier grammar, e.g. {@code (@ Inject (method "m") ...)}. */
    String value();
}
