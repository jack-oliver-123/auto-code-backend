package com.jack.autocodebackend.core.vue;

import com.jack.autocodebackend.config.AppVueProjectProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.function.LongSupplier;

@Component
public final class VueBuilderDependencyProbe {

    private static final Logger log = LoggerFactory.getLogger(
            VueBuilderDependencyProbe.class);

    private final AppVueProjectProperties properties;
    private final BoundedProcessExecutor processExecutor;
    private final LongSupplier nanoTime;
    private final Object cacheMonitor = new Object();

    private volatile CachedResult cachedResult;

    @Autowired
    public VueBuilderDependencyProbe(
            AppVueProjectProperties properties,
            BoundedProcessExecutor processExecutor
    ) {
        this(properties, processExecutor, System::nanoTime);
    }

    VueBuilderDependencyProbe(
            AppVueProjectProperties properties,
            BoundedProcessExecutor processExecutor,
            LongSupplier nanoTime
    ) {
        this.properties = properties;
        this.processExecutor = processExecutor;
        this.nanoTime = nanoTime;
    }

    public boolean checkReadiness() {
        if (!properties.isReadinessRequired()) {
            return true;
        }

        long now = nanoTime.getAsLong();
        CachedResult current = cachedResult;
        if (isFresh(current, now)) {
            return current.available();
        }

        synchronized (cacheMonitor) {
            now = nanoTime.getAsLong();
            current = cachedResult;
            if (isFresh(current, now)) {
                return current.available();
            }
            boolean available = executeProbe();
            cachedResult = new CachedResult(available, nanoTime.getAsLong());
            return available;
        }
    }

    private boolean isFresh(CachedResult result, long now) {
        return result != null
                && now - result.checkedAtNanos()
                < properties.getReadinessCacheTtl().toNanos();
    }

    private boolean executeProbe() {
        try {
            BoundedProcessExecutor.ProcessResult result = processExecutor.execute(
                    List.of(
                            properties.getRuntimeExecutable(),
                            "image",
                            "inspect",
                            "--format",
                            "{{.Id}}",
                            properties.getBuilderImage()
                    ),
                    properties.getReadinessProbeTimeout(),
                    properties.getReadinessDiagnosticMaxBytes()
            );
            if (result.exitCode() == 0) {
                return true;
            }
            return unavailable("command-failed");
        } catch (BoundedProcessExecutor.ProcessTimeoutException exception) {
            return unavailable("timeout");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return unavailable("interrupted");
        } catch (IOException exception) {
            return unavailable("runtime-unavailable");
        } catch (RuntimeException exception) {
            return unavailable("probe-error");
        }
    }

    private boolean unavailable(String category) {
        log.warn("Vue builder dependency probe failed: category={}", category);
        return false;
    }

    private record CachedResult(boolean available, long checkedAtNanos) {
    }
}
