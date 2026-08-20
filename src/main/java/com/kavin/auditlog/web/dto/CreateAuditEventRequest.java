package com.kavin.auditlog.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.Map;

/**
 * Write API request body. {@code occurredAt} is optional: if the caller omits it, the
 * server defaults it to the record's recordedAt (server-assigned append time). See
 * ARCHITECTURE.md "Timestamps" for why both fields exist.
 */
public class CreateAuditEventRequest {

    @NotBlank
    private String eventType;

    @NotBlank
    private String actorId;

    @NotBlank
    private String resourceType;

    @NotBlank
    private String resourceId;

    @NotNull
    private Map<String, Object> payload;

    /** Optional. Caller's claim of when the event happened; not trusted for chain ordering. */
    private Instant occurredAt;

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getActorId() {
        return actorId;
    }

    public void setActorId(String actorId) {
        this.actorId = actorId;
    }

    public String getResourceType() {
        return resourceType;
    }

    public void setResourceType(String resourceType) {
        this.resourceType = resourceType;
    }

    public String getResourceId() {
        return resourceId;
    }

    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public void setPayload(Map<String, Object> payload) {
        this.payload = payload;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }
}
