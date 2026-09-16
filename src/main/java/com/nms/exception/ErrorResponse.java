package com.nms.exception;

import java.time.Instant;
import java.util.List;

public record ErrorResponse(
        String error,
        String message,
        List<String> details,
        Instant timestamp
) {
    public ErrorResponse(String error, String message) {
        this(error, message, List.of(), Instant.now());
    }

    public ErrorResponse(String error, String message, List<String> details) {
        this(error, message, details, Instant.now());
    }
}
