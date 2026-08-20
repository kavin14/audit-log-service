package com.kavin.auditlog.service;

import com.kavin.auditlog.domain.AuditEvent;
import com.kavin.auditlog.repository.AuditEventRepository;
import com.kavin.auditlog.web.dto.AuditEventResponse;
import com.kavin.auditlog.web.dto.CreateAuditEventRequest;
import com.kavin.auditlog.web.dto.PageResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static com.kavin.auditlog.repository.AuditEventSpecifications.*;

@Service
public class AuditEventService {

    private final AuditEventRepository repository;
    private final CanonicalJson canonicalJson;
    private final ChainHasher chainHasher;
    private final PayloadHasher payloadHasher;

    public AuditEventService(AuditEventRepository repository, CanonicalJson canonicalJson, ChainHasher chainHasher,
                              PayloadHasher payloadHasher) {
        this.repository = repository;
        this.canonicalJson = canonicalJson;
        this.chainHasher = chainHasher;
        this.payloadHasher = payloadHasher;
    }

    /**
     * Appends a new record to the chain.
     *
     * <p><b>Concurrency:</b> {@code synchronized} because each new record's previousHash
     * must link to the current tip, and two concurrent appends must not both read the
     * same tip. This serializes writes within a single JVM instance, which is the right
     * trade-off for this prototype's scope but does not hold across multiple instances of
     * the service — a real deployment would need a DB-level advisory lock (e.g. Postgres
     * {@code pg_advisory_xact_lock}) or a single-writer/queue architecture instead. See
     * ARCHITECTURE.md "Known limitations".
     */
    @Transactional
    public synchronized AuditEventResponse append(CreateAuditEventRequest request) {
        String canonicalPayload = canonicalJson.canonicalize(request.getPayload());

        // Scenario B (redaction): hash each payload field individually rather than the payload
        // blob as a whole, so a field can later be redacted (its live value replaced) without
        // invalidating contentHash - see PayloadHasher and ARCHITECTURE.md "Redaction".
        String salt = payloadHasher.newSalt();
        var manifest = payloadHasher.buildManifest(request.getPayload(), salt);
        String manifestJson = canonicalJson.canonicalize(manifest);
        String manifestHash = payloadHasher.manifestHash(manifest);

        Optional<AuditEvent> tip = repository.findTopByOrderBySequenceNumberDesc();
        long nextSequenceNumber = tip.map(e -> e.getSequenceNumber() + 1).orElse(1L);
        String previousHash = tip.map(AuditEvent::getContentHash).orElse(ChainHasher.GENESIS_HASH);

        Instant recordedAt = Instant.now();
        Instant occurredAt = request.getOccurredAt() != null ? request.getOccurredAt() : recordedAt;

        String contentHash = chainHasher.hashContent(nextSequenceNumber, request.getEventType(), request.getActorId(),
                request.getResourceType(), request.getResourceId(), manifestHash, occurredAt, recordedAt);

        AuditEvent event = new AuditEvent(UUID.randomUUID(), nextSequenceNumber, request.getEventType(),
                request.getActorId(), request.getResourceType(), request.getResourceId(), canonicalPayload,
                manifestJson, salt, "[]", occurredAt, recordedAt, contentHash, previousHash);

        repository.save(event);
        return toResponse(event);
    }

    @Transactional(readOnly = true)
    public AuditEventResponse getById(UUID id) {
        AuditEvent event = repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("audit event not found: " + id));
        return toResponse(event);
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditEventResponse> query(String actorId, String resourceType, String resourceId,
                                                    String eventType, Instant from, Instant to,
                                                    boolean includeArchived, Pageable pageable) {
        List<Specification<AuditEvent>> specs = new ArrayList<>(List.of(
                actorIdEquals(actorId),
                resourceTypeEquals(resourceType),
                resourceIdEquals(resourceId),
                eventTypeEquals(eventType),
                occurredFrom(from),
                occurredTo(to)
        ));
        if (!includeArchived) {
            specs.add(notArchived());
        }

        Page<AuditEvent> page = repository.findAll(Specification.allOf(specs), pageable);
        return PageResponse.from(page.map(this::toResponse));
    }

    private AuditEventResponse toResponse(AuditEvent event) {
        return AuditEventResponse.from(event, canonicalJson.parseToMap(event.getPayload()),
                canonicalJson.parseToStringList(event.getRedactedFields()));
    }
}
