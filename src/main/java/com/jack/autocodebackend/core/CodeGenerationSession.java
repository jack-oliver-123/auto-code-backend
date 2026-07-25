package com.jack.autocodebackend.core;

import com.jack.autocodebackend.core.saver.CodeFilePublication;
import com.jack.autocodebackend.exception.BusinessException;
import com.jack.autocodebackend.exception.ErrorCode;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Owns one generated-code stream and its unresolved directory publication.
 */
public final class CodeGenerationSession implements AutoCloseable {

    private enum State {
        ACTIVE,
        COMMITTED,
        ROLLED_BACK
    }

    private final AtomicBoolean subscribed = new AtomicBoolean();
    private Flux<String> codeStream;
    private CodeFilePublication publication;
    private State state = State.ACTIVE;

    CodeGenerationSession() {
    }

    synchronized void initialize(Flux<String> source) {
        if (codeStream != null) {
            throw new IllegalStateException("Code generation session is already initialized");
        }
        Flux<String> initializedSource = Objects.requireNonNull(source);
        codeStream = Flux.defer(() -> {
            if (!subscribed.compareAndSet(false, true)) {
                return Flux.error(new IllegalStateException(
                        "Code generation session supports only one subscription"));
            }
            return initializedSource;
        });
    }

    synchronized void attach(CodeFilePublication generatedPublication) {
        Objects.requireNonNull(generatedPublication);
        if (publication != null) {
            rollbackDetached(generatedPublication);
            throw new IllegalStateException("Code generation publication is already attached");
        }
        publication = generatedPublication;
        if (state == State.ROLLED_BACK) {
            rollbackDetached(generatedPublication);
            throw new IllegalStateException("Code generation session was already rolled back");
        }
        if (state == State.COMMITTED) {
            generatedPublication.commit();
            throw new IllegalStateException("Code generation session was committed too early");
        }
    }

    public synchronized Flux<String> stream() {
        if (codeStream == null) {
            throw new IllegalStateException("Code generation session is not initialized");
        }
        return codeStream;
    }

    public synchronized void commit() {
        if (state == State.COMMITTED) {
            return;
        }
        requireCommittable();
        publication.commit();
        state = State.COMMITTED;
    }

    public synchronized <T> T commitAfter(Supplier<T> finalization) {
        requireCommittable();
        T result = Objects.requireNonNull(finalization, "finalization must not be null").get();
        publication.commit();
        state = State.COMMITTED;
        return result;
    }

    public synchronized void rollback() {
        if (state != State.ACTIVE) {
            return;
        }
        if (publication == null) {
            state = State.ROLLED_BACK;
            return;
        }
        try {
            publication.rollback();
            state = State.ROLLED_BACK;
        } catch (IOException | RuntimeException exception) {
            throw publicationFailure("回滚生成代码失败", exception);
        }
    }

    @Override
    public void close() {
        rollback();
    }

    private void rollbackDetached(CodeFilePublication detachedPublication) {
        try {
            detachedPublication.rollback();
        } catch (IOException | RuntimeException exception) {
            throw publicationFailure("回滚生成代码失败", exception);
        }
    }

    private void requireCommittable() {
        if (state == State.COMMITTED) {
            throw new IllegalStateException("Code generation session is already committed");
        }
        if (state == State.ROLLED_BACK) {
            throw new IllegalStateException("Code generation session is already rolled back");
        }
        if (publication == null) {
            throw new IllegalStateException("Generated code has not been published yet");
        }
    }

    private BusinessException publicationFailure(String message, Exception cause) {
        BusinessException exception = new BusinessException(ErrorCode.OPERATION_ERROR, message);
        exception.initCause(cause);
        return exception;
    }
}
