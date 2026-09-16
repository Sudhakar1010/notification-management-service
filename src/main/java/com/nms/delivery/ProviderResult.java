package com.nms.delivery;

import com.nms.common.FailureType;

public record ProviderResult(boolean success, FailureType failureType, String message) {

    public static ProviderResult success(String message) {
        return new ProviderResult(true, FailureType.NONE, message);
    }

    public static ProviderResult failure(FailureType failureType, String message) {
        return new ProviderResult(false, failureType, message);
    }
}
