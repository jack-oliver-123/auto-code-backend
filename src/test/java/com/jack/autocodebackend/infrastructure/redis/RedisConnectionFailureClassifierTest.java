package com.jack.autocodebackend.infrastructure.redis;

import io.lettuce.core.RedisCommandTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.serializer.SerializationException;

import static org.assertj.core.api.Assertions.assertThat;

class RedisConnectionFailureClassifierTest {

    private final RedisConnectionFailureClassifier classifier =
            new RedisConnectionFailureClassifier();

    @Test
    void findsSupportedConnectionFailuresInBoundedCauseChain() {
        Throwable failure = new IllegalStateException("outer",
                new RedisConnectionFailureException("offline",
                        new RedisCommandTimeoutException("timeout")));

        assertThat(classifier.isConnectionFailure(failure)).isTrue();
    }

    @Test
    void excludesSerializationAndUnrelatedFailures() {
        assertThat(classifier.isConnectionFailure(
                new SerializationException("malformed",
                        new RedisConnectionFailureException("nested")))).isFalse();
        assertThat(classifier.isConnectionFailure(
                new RedisConnectionFailureException("outer",
                        new SerializationException("malformed")))).isFalse();
        assertThat(classifier.isConnectionFailure(
                new IllegalArgumentException("validation"))).isFalse();
    }

    @Test
    void stopsAtDepthLimitAndHandlesCauseCycles() {
        Throwable cause = new RedisConnectionFailureException("too deep");
        for (int i = 0; i < RedisConnectionFailureClassifier.MAX_CAUSE_DEPTH; i++) {
            cause = new RuntimeException("level " + i, cause);
        }
        assertThat(classifier.isConnectionFailure(cause)).isFalse();

        CyclicException cyclic = new CyclicException();
        assertThat(classifier.isConnectionFailure(cyclic)).isFalse();
    }

    private static final class CyclicException extends RuntimeException {

        @Override
        public synchronized Throwable getCause() {
            return this;
        }
    }
}
