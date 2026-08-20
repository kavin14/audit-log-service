package com.kavin.auditlog.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * An append-only audit record. Once persisted, a row is never updated except
 * for the archival fields added for the retention scenario (Scenario B) —
 * every field that participates in {@code contentHash} is immutable after insert.
 */
@Entity
@Table(name = "audit_event")
public class AuditEvent {

    /** Public identifier for API consumers. Not used for chain ordering. */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * Monotonically increasing, DB-assigned chain position. This — not the
     * caller-supplied {@link #occurredAt} — defines chain order, so a client
     * cannot influence its own position in the chain by choosing a timestamp.
     */
    @Column(name = "sequence_number", nullable = false, updatable = false, unique = true)
    private Long sequenceNumber;

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    @Column(name = "actor_id", nullable = false, updatable = false)
    private String actorId;

    @Column(name = "resource_type", nullable = false, updatable = false)
    private String resourceType;

    @Column(name = "resource_id", nullable = false, updatable = false)
    private String resourceId;

    /** Canonical (sorted-key) JSON. Stored canonical so re-hashing at verify time is deterministic. */
    @Lob
    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    /** Caller-supplied "when it happened". Defaults to recordedAt if the caller omits it. */
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    /** Server-assigned "when we appended it". Authoritative for ordering/retention. */
    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    /** SHA-256 hex of this record's own content fields (see CanonicalHasher). */
    @Column(name = "content_hash", nullable = false, updatable = false, length = 64)
    private String contentHash;

    /** contentHash of the previous record in the chain, or GENESIS_HASH for the first record. */
    @Column(name = "previous_hash", nullable = false, updatable = false, length = 64)
    private String previousHash;

    /** Scenario B: retention. Archived records are excluded from normal queries but kept for chain verification. */
    @Column(name = "archived", nullable = false)
    private boolean archived = false;

    @Column(name = "archived_at")
    private Instant archivedAt;

    protected AuditEvent() {
        // JPA
    }

    public AuditEvent(UUID id, Long sequenceNumber, String eventType, String actorId, String resourceType,
                       String resourceId, String payload, Instant occurredAt, Instant recordedAt,
                       String contentHash, String previousHash) {
        this.id = id;
        this.sequenceNumber = sequenceNumber;
        this.eventType = eventType;
        this.actorId = actorId;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.payload = payload;
        this.occurredAt = occurredAt;
        this.recordedAt = recordedAt;
        this.contentHash = contentHash;
        this.previousHash = previousHash;
    }

    public UUID getId() {
        return id;
    }

    public Long getSequenceNumber() {
        return sequenceNumber;
    }

    public String getEventType() {
        return eventType;
    }

    public String getActorId() {
        return actorId;
    }

    public String getResourceType() {
        return resourceType;
    }

    public String getResourceId() {
        return resourceId;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public String getContentHash() {
        return contentHash;
    }

    public String getPreviousHash() {
        return previousHash;
    }

    public boolean isArchived() {
        return archived;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public void archive(Instant at) {
        this.archived = true;
        this.archivedAt = at;
    }
}
