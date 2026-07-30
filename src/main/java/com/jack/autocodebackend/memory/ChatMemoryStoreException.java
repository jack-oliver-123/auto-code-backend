package com.jack.autocodebackend.memory;

public class ChatMemoryStoreException extends RuntimeException {

    public ChatMemoryStoreException(String message) {
        super(message);
    }

    public ChatMemoryStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
