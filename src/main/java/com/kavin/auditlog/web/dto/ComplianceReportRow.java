package com.kavin.auditlog.web.dto;

import java.time.Instant;

public record ComplianceReportRow(
        long sequenceNumber,
        String eventType,
        String actorId,
        Instant occurredAt,
        Instant recordedAt,
        String summary
) {
}
