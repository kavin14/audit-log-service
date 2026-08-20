package com.kavin.auditlog.web.dto;

import com.kavin.auditlog.domain.AuditEvent;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything about a record needed for a recipient to independently re-derive and check its
 * hashes, not just display it - unlike {@link AuditEventResponse}, this also carries the payload
 * field-hash manifest and salt (see PayloadHasher), since those are required inputs to recompute
 * contentHash.
 */
public record ExportedRecord(
        UUID id,
        long sequenceNumber,
        String eventType,
        String actorId,
        String resourceType,
        String resourceId,
        Map<String, Object> payload,
        Map<String, String> payloadFieldHashes,
        String payloadSalt,
        List<String> redactedFields,
        Instant occurredAt,
        Instant recordedAt,
        String contentHash,
        String previousHash,
        boolean archived
) {
    public static ExportedRecord from(AuditEvent event, Map<String, Object> payload,
                                       Map<String, String> payloadFieldHashes, List<String> redactedFields) {
        return new ExportedRecord(event.getId(), event.getSequenceNumber(), event.getEventType(),
                event.getActorId(), event.getResourceType(), event.getResourceId(), payload, payloadFieldHashes,
                event.getPayloadSalt(), redactedFields, event.getOccurredAt(), event.getRecordedAt(),
                event.getContentHash(), event.getPreviousHash(), event.isArchived());
    }
}
