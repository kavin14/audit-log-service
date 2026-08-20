package com.kavin.auditlog.web.dto;

import com.kavin.auditlog.domain.AuditEvent;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditEventResponse(
        UUID id,
        long sequenceNumber,
        String eventType,
        String actorId,
        String resourceType,
        String resourceId,
        Map<String, Object> payload,
        Instant occurredAt,
        Instant recordedAt,
        String contentHash,
        String previousHash,
        boolean archived,
        Instant archivedAt
) {
    public static AuditEventResponse from(AuditEvent event, Map<String, Object> parsedPayload) {
        return new AuditEventResponse(
                event.getId(),
                event.getSequenceNumber(),
                event.getEventType(),
                event.getActorId(),
                event.getResourceType(),
                event.getResourceId(),
                parsedPayload,
                event.getOccurredAt(),
                event.getRecordedAt(),
                event.getContentHash(),
                event.getPreviousHash(),
                event.isArchived(),
                event.getArchivedAt()
        );
    }
}
