package com.kavin.auditlog.service;

import com.kavin.auditlog.domain.AuditEvent;
import com.kavin.auditlog.repository.AuditEventRepository;
import com.kavin.auditlog.web.dto.ComplianceReportResponse;
import com.kavin.auditlog.web.dto.ComplianceReportRow;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.kavin.auditlog.repository.AuditEventSpecifications.*;

/**
 * Scenario C: "regulators need to be able to audit access to client account data," clarified -
 * see SCENARIO_C.md for the ambiguities identified and the assumptions this design makes.
 * Deliberately a thin presentation layer over Scenario A's existing tamper-evident storage and
 * query machinery, not a new subsystem: the clarified requirement is about *reporting*, not new
 * write paths.
 */
@Service
public class ComplianceReportService {

    private final AuditEventRepository repository;
    private final CanonicalJson canonicalJson;
    private final ChainVerificationService chainVerificationService;

    public ComplianceReportService(AuditEventRepository repository, CanonicalJson canonicalJson,
                                    ChainVerificationService chainVerificationService) {
        this.repository = repository;
        this.canonicalJson = canonicalJson;
        this.chainVerificationService = chainVerificationService;
    }

    @Transactional(readOnly = true)
    public ComplianceReportResponse generate(String resourceType, String resourceId, Instant from, Instant to,
                                              boolean includeArchived) {
        if (resourceId == null || resourceId.isBlank()) {
            throw new IllegalArgumentException("resourceId is required");
        }

        List<Specification<AuditEvent>> specs = new ArrayList<>(List.of(
                resourceTypeEquals(resourceType),
                resourceIdEquals(resourceId),
                occurredFrom(from),
                occurredTo(to)
        ));
        if (!includeArchived) {
            specs.add(notArchived());
        }

        List<AuditEvent> events = repository.findAll(Specification.allOf(specs), Sort.by(Sort.Direction.ASC, "sequenceNumber"));

        List<ComplianceReportRow> rows = events.stream()
                .map(this::toRow)
                .toList();

        boolean chainIntact = chainVerificationService.verify().intact();

        return new ComplianceReportResponse(resourceType, resourceId, from, to, chainIntact, rows);
    }

    private ComplianceReportRow toRow(AuditEvent event) {
        Map<String, Object> payload = canonicalJson.parseToMap(event.getPayload());
        String summary = summarize(payload);
        return new ComplianceReportRow(event.getSequenceNumber(), event.getEventType(), event.getActorId(),
                event.getOccurredAt(), event.getRecordedAt(), summary);
    }

    /** A short, human-readable line from the payload - a regulator reading a CSV row shouldn't need to parse JSON. */
    private String summarize(Map<String, Object> payload) {
        if (payload.isEmpty()) {
            return "";
        }
        List<String> parts = payload.entrySet().stream()
                .limit(4)
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.toList());
        if (payload.size() > 4) {
            parts.add("...");
        }
        return String.join(", ", parts);
    }
}
