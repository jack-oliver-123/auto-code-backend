package com.jack.autocodebackend.core.deploy;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SecureRandomDeployKeyGeneratorTest {

    @Test
    void mapsSecureRandomIndexesAcrossTheExactAlphabet() {
        SecureRandom secureRandom = mock(SecureRandom.class);
        when(secureRandom.nextInt(SecureRandomDeployKeyGenerator.ALPHABET.length()))
                .thenReturn(0, 25, 26, 51, 52, 61);

        String key = new SecureRandomDeployKeyGenerator(secureRandom).generate();

        assertEquals("AZaz09", key);
        verify(secureRandom, times(6)).nextInt(SecureRandomDeployKeyGenerator.ALPHABET.length());
    }

    @Test
    void defaultGeneratorAlwaysProducesSixAlphanumericCharacters() {
        DeployKeyGenerator generator = new SecureRandomDeployKeyGenerator();

        for (int i = 0; i < 100; i++) {
            assertTrue(generator.generate().matches("[A-Za-z0-9]{6}"));
        }
    }
}
