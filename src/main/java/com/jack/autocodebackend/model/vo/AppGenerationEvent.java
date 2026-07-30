package com.jack.autocodebackend.model.vo;

/**
 * Events emitted while generating an application.
 */
public sealed interface AppGenerationEvent
        permits AppGenerationEvent.Content, AppGenerationEvent.Heartbeat,
        AppGenerationEvent.Completed, AppGenerationEvent.Failed {

    /**
     * A byte-for-byte application-layer chunk from the AI stream.
     */
    record Content(String chunk) implements AppGenerationEvent {
    }

    /**
     * Transport-only keepalive. It is serialized as an SSE comment.
     */
    record Heartbeat() implements AppGenerationEvent {
    }

    /**
     * Emitted only after generated files and preview access are ready.
     */
    record Completed(AppPreviewVO preview) implements AppGenerationEvent {
    }

    /**
     * The sole writable terminal event for an asynchronous generation failure.
     */
    record Failed(int code, String message, String status) implements AppGenerationEvent {
    }

    default boolean isTerminal() {
        return this instanceof Completed || this instanceof Failed;
    }
}
