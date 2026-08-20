package com.kavin.auditlog.repository;

import com.kavin.auditlog.domain.AuditEvent;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;

public final class AuditEventSpecifications {

    private AuditEventSpecifications() {
    }

    public static Specification<AuditEvent> actorIdEquals(String actorId) {
        return (root, query, cb) -> actorId == null ? null : cb.equal(root.get("actorId"), actorId);
    }

    public static Specification<AuditEvent> resourceTypeEquals(String resourceType) {
        return (root, query, cb) -> resourceType == null ? null : cb.equal(root.get("resourceType"), resourceType);
    }

    public static Specification<AuditEvent> resourceIdEquals(String resourceId) {
        return (root, query, cb) -> resourceId == null ? null : cb.equal(root.get("resourceId"), resourceId);
    }

    public static Specification<AuditEvent> eventTypeEquals(String eventType) {
        return (root, query, cb) -> eventType == null ? null : cb.equal(root.get("eventType"), eventType);
    }

    /** Filters on occurredAt (the caller-supplied "when it happened" field), not recordedAt. */
    public static Specification<AuditEvent> occurredFrom(Instant from) {
        return (root, query, cb) -> from == null ? null : cb.greaterThanOrEqualTo(root.get("occurredAt"), from);
    }

    public static Specification<AuditEvent> occurredTo(Instant to) {
        return (root, query, cb) -> to == null ? null : cb.lessThanOrEqualTo(root.get("occurredAt"), to);
    }

    /** Normal queries hide archived (Scenario B) records by default; verification never uses this. */
    public static Specification<AuditEvent> notArchived() {
        return (root, query, cb) -> cb.isFalse(root.get("archived"));
    }
}
