package com.kavin.auditlog.web.dto;

import com.kavin.auditlog.service.RetentionService;

import java.time.Instant;

public record ArchiveResponse(int archivedCount, int windowDays, Instant cutoff) {
    public static ArchiveResponse from(RetentionService.ArchiveResult result) {
        return new ArchiveResponse(result.archivedCount(), result.windowDays(), result.cutoff());
    }
}
