package com.momosoftworks.kawaforge.mixin.meta;

public final class PayloadException extends RuntimeException {
    public final int offset;

    public PayloadException(String message, int offset) {
        super(String.format("%s [at offset %d]", message, offset));
        this.offset = offset;
    }
}
