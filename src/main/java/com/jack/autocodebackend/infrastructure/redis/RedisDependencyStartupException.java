package com.jack.autocodebackend.infrastructure.redis;

public class RedisDependencyStartupException extends IllegalStateException {

    public RedisDependencyStartupException(String message) {
        super(message);
    }
}
