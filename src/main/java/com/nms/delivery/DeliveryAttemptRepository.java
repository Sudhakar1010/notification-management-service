package com.nms.delivery;

import com.nms.common.DeliveryStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface DeliveryAttemptRepository extends JpaRepository<DeliveryAttempt, UUID> {

    List<DeliveryAttempt> findByNotificationId(UUID notificationId);

    List<DeliveryAttempt> findTop50ByStatusInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            List<DeliveryStatus> statuses, Instant now);
}
