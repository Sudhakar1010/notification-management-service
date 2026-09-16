package com.nms.exception;

public class InvalidNotificationRequestException extends RuntimeException {

    public InvalidNotificationRequestException(String message) {
        super(message);
    }
}
