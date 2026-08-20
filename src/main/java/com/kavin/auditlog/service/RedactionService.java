package com.kavin.auditlog.service;

import com.kavin.auditlog.domain.AuditEvent;
import com.kavin.auditlog.repository.AuditEventRepository;
import com.kavin.auditlog.web.dto.AuditEventResponse;
import com.kavin.auditlog.web.dto.CreateAuditEventRequest;
import com.kavin.auditlog.web.dto.RedactRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

/**
 * Redacts fields from an already-written record's payload without invalidating the hash chain.
 * See PayloadHasher and ARCHITECTURE.md "Redaction" for the underlying scheme; this service is
 * the write path that uses it.
 */
@Service
public class RedactionService {

    private final AuditEventRepository repository;
    private final CanonicalJson canonicalJson;
    private final AuditEventService auditEventService;

    public RedactionService(AuditEventRepository repository, CanonicalJson canonicalJson,
                             AuditEventService auditEventService) {
        this.repository = repository;
        this.canonicalJson = canonicalJson;
        this.auditEventService = auditEventService;
    }

    /**
     * Replaces the requested fields' live values with a placeholder and appends a companion
     * {@code PAYLOAD_REDACTED} event to the chain, recording who redacted what and why. Making
     * the redaction itself a chained, tamper-evident event is a deliberate mitigation for this
     * scheme's core limitation: verification trusts a redacted field's preserved commitment
     * without re-checking it, so an attacker with raw DB write access could in principle both
     * alter a redacted field's placeholder value AND leave redactedFields untouched without
     * detection at the field-check layer (see PayloadHasherTest
     * tamperingWithARedactedFieldsPlaceholderIsNotFlaggedByFieldCheck). The companion event
     * doesn't close that gap by itself, but it does mean every *legitimate* redaction leaves an
     * auditable trail that a reviewer can cross-check against which fields a record currently
     * shows as redacted - a discrepancy there is itself a signal worth investigating.
     *
     * <p>Idempotent: fields already redacted are silently skipped. If every requested field was
     * already redacted, no companion event is appended (nothing changed).
     */
    @Transactional
    public AuditEventResponse redact(UUID targetId, RedactRequest request) {
        AuditEvent target = repository.findById(targetId)
                .orElseThrow(() -> new NoSuchElementException("audit event not found: " + targetId));

        Map<String, Object> payload = canonicalJson.parseToMap(target.getPayload());
        Set<String> redacted = new LinkedHashSet<>(canonicalJson.parseToStringList(target.getRedactedFields()));

        List<String> unknownFields = request.getFields().stream()
                .filter(f -> !payload.containsKey(f))
                .toList();
        if (!unknownFields.isEmpty()) {
            throw new IllegalArgumentException("unknown payload field(s): " + unknownFields);
        }

        List<String> newlyRedacted = new ArrayList<>();
        for (String field : request.getFields()) {
            if (redacted.add(field)) {
                payload.put(field, "[REDACTED]");
                newlyRedacted.add(field);
            }
        }

        if (newlyRedacted.isEmpty()) {
            return toResponse(target);
        }

        target.applyRedaction(canonicalJson.canonicalize(payload), canonicalJson.canonicalizeList(List.copyOf(redacted)));
        repository.save(target);

        appendRedactionAuditTrail(target, request, newlyRedacted);

        return toResponse(target);
    }

    private void appendRedactionAuditTrail(AuditEvent target, RedactRequest request, List<String> newlyRedacted) {
        Map<String, Object> trailPayload = new LinkedHashMap<>();
        trailPayload.put("action", "REDACTION");
        trailPayload.put("targetEventId", target.getId().toString());
        trailPayload.put("targetSequenceNumber", target.getSequenceNumber());
        trailPayload.put("redactedFields", newlyRedacted);
        if (request.getReason() != null) {
            trailPayload.put("reason", request.getReason());
        }

        CreateAuditEventRequest trailEvent = new CreateAuditEventRequest();
        trailEvent.setEventType("PAYLOAD_REDACTED");
        trailEvent.setActorId(request.getActorId());
        trailEvent.setResourceType(target.getResourceType());
        trailEvent.setResourceId(target.getResourceId());
        trailEvent.setPayload(trailPayload);
        auditEventService.append(trailEvent);
    }

    private AuditEventResponse toResponse(AuditEvent event) {
        return AuditEventResponse.from(event, canonicalJson.parseToMap(event.getPayload()),
                canonicalJson.parseToStringList(event.getRedactedFields()));
    }
}
