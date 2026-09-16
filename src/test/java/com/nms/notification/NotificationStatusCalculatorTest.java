package com.nms.notification;

import com.nms.common.Channel;
import com.nms.common.DeliveryStatus;
import com.nms.common.NotificationStatus;
import com.nms.delivery.DeliveryAttempt;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationStatusCalculatorTest {

    private static DeliveryAttempt attempt(DeliveryStatus status) {
        DeliveryAttempt a = new DeliveryAttempt();
        a.setChannel(Channel.EMAIL);
        a.setStatus(status);
        return a;
    }

    @Test
    void processingWhenAnyAttemptStillInFlight() {
        var status = NotificationStatusCalculator.compute(
                List.of(attempt(DeliveryStatus.SUCCEEDED), attempt(DeliveryStatus.QUEUED)), 0);
        assertThat(status).isEqualTo(NotificationStatus.PROCESSING);
    }

    @Test
    void deliveredWhenEverythingSucceeded() {
        var status = NotificationStatusCalculator.compute(
                List.of(attempt(DeliveryStatus.SUCCEEDED), attempt(DeliveryStatus.SUCCEEDED)), 0);
        assertThat(status).isEqualTo(NotificationStatus.DELIVERED);
    }

    @Test
    void failedWhenNothingSucceeded() {
        var status = NotificationStatusCalculator.compute(List.of(attempt(DeliveryStatus.FAILED)), 0);
        assertThat(status).isEqualTo(NotificationStatus.FAILED);
    }

    @Test
    void failedWhenNoDeliverableUnitsExistAtAll() {
        var status = NotificationStatusCalculator.compute(List.of(), 0);
        assertThat(status).isEqualTo(NotificationStatus.FAILED);
    }

    @Test
    void partiallyDeliveredWhenMixOfSuccessAndFailure() {
        var status = NotificationStatusCalculator.compute(
                List.of(attempt(DeliveryStatus.SUCCEEDED), attempt(DeliveryStatus.EXHAUSTED)), 0);
        assertThat(status).isEqualTo(NotificationStatus.PARTIALLY_DELIVERED);
    }

    @Test
    void recipientsWithNoRouteCountAsFailedUnits() {
        var status = NotificationStatusCalculator.compute(List.of(attempt(DeliveryStatus.SUCCEEDED)), 1);
        assertThat(status).isEqualTo(NotificationStatus.PARTIALLY_DELIVERED);
    }
}
