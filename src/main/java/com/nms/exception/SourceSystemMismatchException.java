package com.nms.exception;

/**
 * Thrown when the authenticated caller's identity (from the API key)
 * doesn't match the sourceSystem field in the request body -- see
 * ARCHITECTURE.md ADR-018.
 */
public class SourceSystemMismatchException extends RuntimeException {

    public SourceSystemMismatchException(String message) {
        super(message);
    }
}
