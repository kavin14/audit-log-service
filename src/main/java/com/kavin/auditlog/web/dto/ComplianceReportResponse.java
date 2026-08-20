package com.kavin.auditlog.web.dto;

import java.time.Instant;
import java.util.List;

public record ComplianceReportResponse(
        String resourceType,
        String resourceId,
        Instant from,
        Instant to,
        boolean chainIntact,
        List<ComplianceReportRow> rows
) {
}
