package com.kavin.auditlog.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A self-contained, independently verifiable slice of the audit log for one resourceId or
 * actorId. See ARCHITECTURE.md "Bulk export" for exactly what "independently verifiable" does
 * and doesn't guarantee here.
 */
public record ExportBundle(
        Instant exportedAt,
        Map<String, String> filter,
        String previousHashBeforeBundle,
        List<ExportedRecord> records,
        String bundleHash
) {
}
