package com.momosoftworks.kawaforge.mixin;

public final class MixinProcessingException extends RuntimeException {
    public MixinProcessingException(String message, Throwable cause) {
        super(message, cause);
    }

    public MixinProcessingException(String message) {
        super(message);
    }
}
