package com.jack.autocodebackend.infrastructure.redis;

import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisConnectionException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.PoolException;
import org.springframework.data.redis.serializer.SerializationException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

public class RedisConnectionFailureClassifier {

    static final int MAX_CAUSE_DEPTH = 12;

    public boolean isConnectionFailure(Throwable throwable) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Throwable> causeChain = new ArrayList<>(MAX_CAUSE_DEPTH);
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < MAX_CAUSE_DEPTH && visited.add(current)) {
            causeChain.add(current);
            current = current.getCause();
            depth++;
        }
        for (Throwable cause : causeChain) {
            if (cause instanceof SerializationException) {
                return false;
            }
        }
        for (Throwable cause : causeChain) {
            if (cause instanceof RedisConnectionFailureException
                    || cause instanceof RedisConnectionException
                    || cause instanceof RedisCommandTimeoutException
                    || cause instanceof PoolException) {
                return true;
            }
        }
        return false;
    }
}
