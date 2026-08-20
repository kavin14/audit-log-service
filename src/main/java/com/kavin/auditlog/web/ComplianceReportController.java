package com.kavin.auditlog.web;

import com.kavin.auditlog.service.ComplianceReportService;
import com.kavin.auditlog.web.dto.ComplianceReportResponse;
import com.kavin.auditlog.web.dto.ComplianceReportRow;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/** Scenario C - see SCENARIO_C.md for the clarified requirement this implements. */
@RestController
@RequestMapping("/audit/compliance-report")
public class ComplianceReportController {

    private final ComplianceReportService reportService;

    public ComplianceReportController(ComplianceReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping
    public ResponseEntity<?> report(
            @RequestParam String resourceId,
            @RequestParam(defaultValue = "ACCOUNT") String resourceType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "true") boolean includeArchived,
            @RequestParam(defaultValue = "json") String format
    ) {
        ComplianceReportResponse report = reportService.generate(resourceType, resourceId, from, to, includeArchived);

        if ("csv".equalsIgnoreCase(format)) {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                            .filename(resourceType + "_" + resourceId + "_access_report.csv", StandardCharsets.UTF_8)
                            .build().toString())
                    .body(toCsv(report));
        }
        return ResponseEntity.ok(report);
    }

    private String toCsv(ComplianceReportResponse report) {
        StringBuilder csv = new StringBuilder();
        csv.append("# resourceType=").append(report.resourceType())
                .append(" resourceId=").append(report.resourceId())
                .append(" chainIntact=").append(report.chainIntact()).append("\r\n");
        csv.append(CsvWriter.row(List.of("sequenceNumber", "eventType", "actorId", "occurredAt", "recordedAt", "summary")));
        for (ComplianceReportRow row : report.rows()) {
            csv.append(CsvWriter.row(List.of(row.sequenceNumber(), row.eventType(), row.actorId(),
                    row.occurredAt(), row.recordedAt(), row.summary())));
        }
        return csv.toString();
    }
}
