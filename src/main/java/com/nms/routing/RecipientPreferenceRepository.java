package com.nms.routing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RecipientPreferenceRepository extends JpaRepository<RecipientPreference, UUID> {

    List<RecipientPreference> findByRecipientId(String recipientId);
}
