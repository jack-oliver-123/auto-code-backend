package com.jack.autocodebackend.core.deploy;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Objects;

@Component
public final class SecureRandomDeployKeyGenerator implements DeployKeyGenerator {

    static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private static final int KEY_LENGTH = 6;

    private final SecureRandom secureRandom;

    public SecureRandomDeployKeyGenerator() {
        this(new SecureRandom());
    }

    SecureRandomDeployKeyGenerator(SecureRandom secureRandom) {
        this.secureRandom = Objects.requireNonNull(secureRandom);
    }

    @Override
    public String generate() {
        StringBuilder key = new StringBuilder(KEY_LENGTH);
        for (int i = 0; i < KEY_LENGTH; i++) {
            key.append(ALPHABET.charAt(secureRandom.nextInt(ALPHABET.length())));
        }
        return key.toString();
    }
}
