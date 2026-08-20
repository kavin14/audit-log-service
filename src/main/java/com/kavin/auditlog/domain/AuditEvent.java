package com.kavin.auditlog.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * An append-only audit record. Most fields are immutable after insert - the exceptions are the
 * archival fields (Scenario B retention) and {@code payload}/{@code redactedFields} (Scenario B
 * redaction), which can change in ways that do not affect {@code contentHash}. See
 * ARCHITECTURE.md "Redaction" for why payload can mutate without invalidating the hash chain.
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

    /**
     * Canonical (sorted-key) JSON of the current field values. Mutable: a redaction replaces a
     * field's value with a placeholder here. Never hashed directly - see {@link #payloadFieldHashes}.
     */
    @Lob
    @Column(name = "payload", nullable = false)
    private String payload;

    /**
     * Canonical JSON of {fieldName: saltedCommitmentHash}, computed once from the *original*
     * payload at write time. Immutable - this is what actually feeds contentHash, so redacting a
     * field's live value in {@link #payload} never invalidates the chain.
     */
    @Lob
    @Column(name = "payload_field_hashes", nullable = false, updatable = false)
    private String payloadFieldHashes;

    /** Per-record random salt used when computing payloadFieldHashes. Immutable. */
    @Column(name = "payload_salt", nullable = false, updatable = false, length = 32)
    private String payloadSalt;

    /** Canonical JSON array of field names that have been redacted. Mutable; starts as "[]". */
    @Lob
    @Column(name = "redacted_fields", nullable = false)
    private String redactedFields;

    /** Caller-supplied "when it happened". Defaults to recordedAt if the caller omits it. */
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    /** Server-assigned "when we appended it". Authoritative for ordering/retention. */
    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    /** SHA-256 hex of this record's own content fields, built from payloadFieldHashes (see ChainHasher). */
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
                       String resourceId, String payload, String payloadFieldHashes, String payloadSalt,
                       String redactedFields, Instant occurredAt, Instant recordedAt,
                       String contentHash, String previousHash) {
        this.id = id;
        this.sequenceNumber = sequenceNumber;
        this.eventType = eventType;
        this.actorId = actorId;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.payload = payload;
        this.payloadFieldHashes = payloadFieldHashes;
        this.payloadSalt = payloadSalt;
        this.redactedFields = redactedFields;
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

    public String getPayloadFieldHashes() {
        return payloadFieldHashes;
    }

    public String getPayloadSalt() {
        return payloadSalt;
    }

    public String getRedactedFields() {
        return redactedFields;
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

    /** Replaces the payload text (fields redacted to placeholders) and the redacted-fields list. */
    public void applyRedaction(String newPayload, String newRedactedFields) {
        this.payload = newPayload;
        this.redactedFields = newRedactedFields;
    }
}
