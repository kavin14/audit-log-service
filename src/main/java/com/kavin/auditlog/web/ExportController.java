package com.kavin.auditlog.web;

import com.kavin.auditlog.service.BundleVerificationService;
import com.kavin.auditlog.service.ExportService;
import com.kavin.auditlog.web.dto.ChainVerificationResponse;
import com.kavin.auditlog.web.dto.ExportBundle;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/audit/export")
public class ExportController {

    private final ExportService exportService;
    private final BundleVerificationService bundleVerificationService;

    public ExportController(ExportService exportService, BundleVerificationService bundleVerificationService) {
        this.exportService = exportService;
        this.bundleVerificationService = bundleVerificationService;
    }

    @GetMapping
    public ExportBundle export(@RequestParam(required = false) String resourceId,
                                @RequestParam(required = false) String actorId,
                                @RequestParam(defaultValue = "true") boolean includeArchived) {
        return exportService.export(resourceId, actorId, includeArchived);
    }

    /**
     * Reruns the same checks a recipient would run independently, against exactly the JSON they
     * received - no database access. Exposed here mainly to make "the bundle is independently
     * verifiable" concretely demonstrable rather than just asserted.
     */
    @PostMapping("/verify")
    public ChainVerificationResponse verifyBundle(@RequestBody ExportBundle bundle) {
        return bundleVerificationService.verify(bundle);
    }
}
