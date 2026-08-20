package com.kavin.auditlog.web;

import com.kavin.auditlog.service.RetentionService;
import com.kavin.auditlog.web.dto.ArchiveResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/audit/retention")
public class RetentionController {

    private final RetentionService retentionService;

    public RetentionController(RetentionService retentionService) {
        this.retentionService = retentionService;
    }

    /**
     * Archives records older than the configured window (audit.retention.archive-after-days,
     * default 90). olderThanDays overrides the configured window for this call only - useful for
     * demonstrating/testing retention without waiting for real time to pass.
     */
    @PostMapping("/archive")
    public ArchiveResponse archive(@RequestParam(required = false) Integer olderThanDays) {
        return ArchiveResponse.from(retentionService.archiveEligible(olderThanDays));
    }
}
