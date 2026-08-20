package com.kavin.auditlog.web;

import com.kavin.auditlog.service.AuditEventService;
import com.kavin.auditlog.service.RedactionService;
import com.kavin.auditlog.web.dto.AuditEventResponse;
import com.kavin.auditlog.web.dto.CreateAuditEventRequest;
import com.kavin.auditlog.web.dto.PageResponse;
import com.kavin.auditlog.web.dto.RedactRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/audit/events")
public class AuditEventController {

    private final AuditEventService service;
    private final RedactionService redactionService;

    public AuditEventController(AuditEventService service, RedactionService redactionService) {
        this.service = service;
        this.redactionService = redactionService;
    }

    @PostMapping
    public ResponseEntity<AuditEventResponse> create(@Valid @RequestBody CreateAuditEventRequest request) {
        AuditEventResponse created = service.append(request);
        return ResponseEntity.created(URI.create("/audit/events/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    public AuditEventResponse getById(@PathVariable UUID id) {
        return service.getById(id);
    }

    /**
     * Scenario B: redacts one or more payload fields on an already-written record without
     * invalidating the hash chain. Not a PATCH on the resource in the REST sense - it's a
     * narrow, purpose-built operation (see RedactionService), and it appends a companion
     * PAYLOAD_REDACTED event rather than silently mutating history.
     */
    @PostMapping("/{id}/redact")
    public AuditEventResponse redact(@PathVariable UUID id, @Valid @RequestBody RedactRequest request) {
        return redactionService.redact(id, request);
    }

    /** Records are append-only: intentionally no PUT/PATCH/DELETE mapping in this controller. */
    @GetMapping
    public PageResponse<AuditEventResponse> query(
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "false") boolean includeArchived,
            @PageableDefault(size = 20, sort = "sequenceNumber", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return service.query(actorId, resourceType, resourceId, eventType, from, to, includeArchived, pageable);
    }
}
