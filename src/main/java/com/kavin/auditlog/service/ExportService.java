package com.kavin.auditlog.service;

import com.kavin.auditlog.domain.AuditEvent;
import com.kavin.auditlog.repository.AuditEventRepository;
import com.kavin.auditlog.web.dto.ExportBundle;
import com.kavin.auditlog.web.dto.ExportedRecord;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.kavin.auditlog.repository.AuditEventSpecifications.*;

/**
 * Scenario B: bulk export. Produces a bundle a recipient can verify without access to the rest
 * of the chain or this service - see ARCHITECTURE.md "Bulk export" for exactly what that
 * verification does and does not guarantee (in short: internal consistency and "unaltered since
 * export", not a cryptographic signature of authenticity).
 */
@Service
public class ExportService {

    private final AuditEventRepository repository;
    private final CanonicalJson canonicalJson;
    private final ChainHasher chainHasher;

    public ExportService(AuditEventRepository repository, CanonicalJson canonicalJson, ChainHasher chainHasher) {
        this.repository = repository;
        this.canonicalJson = canonicalJson;
        this.chainHasher = chainHasher;
    }

    @Transactional(readOnly = true)
    public ExportBundle export(String resourceId, String actorId, boolean includeArchived) {
        if ((resourceId == null) == (actorId == null)) {
            throw new IllegalArgumentException("exactly one of resourceId or actorId is required");
        }

        List<Specification<AuditEvent>> specs = new java.util.ArrayList<>(List.of(
                resourceId != null ? resourceIdEquals(resourceId) : actorIdEquals(actorId)
        ));
        if (!includeArchived) {
            specs.add(notArchived());
        }

        List<AuditEvent> events = repository.findAll(Specification.allOf(specs), Sort.by(Sort.Direction.ASC, "sequenceNumber"));

        List<ExportedRecord> records = events.stream()
                .map(this::toExportedRecord)
                .toList();

        String previousHashBeforeBundle = records.isEmpty()
                ? ChainHasher.GENESIS_HASH
                : records.get(0).previousHash();

        Map<String, String> filter = new LinkedHashMap<>();
        if (resourceId != null) {
            filter.put("resourceId", resourceId);
        } else {
            filter.put("actorId", actorId);
        }
        filter.put("includeArchived", String.valueOf(includeArchived));

        Instant exportedAt = Instant.now();
        String bundleHash = computeBundleHash(records, filter, previousHashBeforeBundle, exportedAt);

        return new ExportBundle(exportedAt, filter, previousHashBeforeBundle, records, bundleHash);
    }

    /**
     * Detects accidental corruption or a naive edit to the bundle file after export (a record
     * removed, added, reordered, or its contentHash altered). It is NOT a digital signature: an
     * attacker who can rewrite the bundle file can also recompute and rewrite this field to
     * match. Real non-repudiation would need this hash (or the bundle) signed with the service's
     * private key, so a recipient can verify authenticity against a published public key - out
     * of scope for this prototype, noted as a limitation in the final engineering summary.
     */
    private String computeBundleHash(List<ExportedRecord> records, Map<String, String> filter,
                                      String previousHashBeforeBundle, Instant exportedAt) {
        List<Map<String, Object>> summary = records.stream()
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("sequenceNumber", r.sequenceNumber());
                    m.put("contentHash", r.contentHash());
                    return m;
                })
                .toList();

        // See ChainHasher.canonicalInstant: Instant.toString() is not safe here either, since
        // verification recomputes this hash from an exportedAt that round-tripped through JSON.
        Map<String, Object> forHashing = new LinkedHashMap<>();
        forHashing.put("exportedAt", exportedAt.getEpochSecond() + "." + exportedAt.getNano());
        forHashing.put("filter", filter);
        forHashing.put("previousHashBeforeBundle", previousHashBeforeBundle);
        forHashing.put("records", summary);

        return chainHasher.sha256Hex(canonicalJson.canonicalize(forHashing));
    }

    private ExportedRecord toExportedRecord(AuditEvent event) {
        Map<String, Object> payload = canonicalJson.parseToMap(event.getPayload());
        Map<String, Object> rawManifest = canonicalJson.parseToMap(event.getPayloadFieldHashes());
        Map<String, String> manifest = new LinkedHashMap<>();
        rawManifest.forEach((k, v) -> manifest.put(k, String.valueOf(v)));
        List<String> redactedFields = canonicalJson.parseToStringList(event.getRedactedFields());
        return ExportedRecord.from(event, payload, manifest, redactedFields);
    }
}
