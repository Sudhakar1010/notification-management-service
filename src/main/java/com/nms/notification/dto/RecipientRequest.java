package com.nms.notification.dto;

import jakarta.validation.constraints.NotBlank;

public record RecipientRequest(
        @NotBlank(message = "recipientId is required")
        String recipientId
) {
}
