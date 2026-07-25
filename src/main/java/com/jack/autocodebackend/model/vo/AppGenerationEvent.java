package com.jack.autocodebackend.model.vo;

/**
 * Events emitted while generating an application.
 */
public sealed interface AppGenerationEvent
        permits AppGenerationEvent.Content, AppGenerationEvent.Completed {

    /**
     * A byte-for-byte application-layer chunk from the AI stream.
     */
    record Content(String chunk) implements AppGenerationEvent {
    }

    /**
     * Emitted only after generated files and preview access are ready.
     */
    record Completed(AppPreviewVO preview) implements AppGenerationEvent {
    }
}
