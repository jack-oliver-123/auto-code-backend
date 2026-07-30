package com.jack.autocodebackend.infrastructure.redis;

import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class RedisDependencyAvailability {

    private final ApplicationEventPublisher eventPublisher;

    private final AtomicBoolean available = new AtomicBoolean(false);

    public RedisDependencyAvailability(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public boolean isAvailable() {
        return available.get();
    }

    public void markAvailable() {
        if (available.compareAndSet(false, true)) {
            AvailabilityChangeEvent.publish(
                    eventPublisher, this, ReadinessState.ACCEPTING_TRAFFIC);
        }
    }

    public void markUnavailable() {
        if (available.compareAndSet(true, false)) {
            AvailabilityChangeEvent.publish(
                    eventPublisher, this, ReadinessState.REFUSING_TRAFFIC);
        }
    }
}
