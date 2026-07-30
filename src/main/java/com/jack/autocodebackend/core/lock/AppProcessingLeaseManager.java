package com.jack.autocodebackend.core.lock;

import reactor.core.publisher.Mono;

public interface AppProcessingLeaseManager {

    AppProcessingLease acquire(Long appId);

    /**
     * Checks whether another process may still own the application lease.
     * UNKNOWN must always be treated as potentially present.
     */
    default LeasePresence checkPresence(Long appId) {
        return LeasePresence.UNKNOWN;
    }

    enum LeasePresence {
        PRESENT,
        ABSENT,
        UNKNOWN
    }

    interface AppProcessingLease extends AutoCloseable {

        long appId();

        boolean isLost();

        void assertHeld();

        Mono<Void> lossSignal();

        @Override
        void close();
    }
}
