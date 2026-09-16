package com.nms.notification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    Optional<Notification> findBySourceSystemAndIdempotencyKey(String sourceSystem, String idempotencyKey);
}
