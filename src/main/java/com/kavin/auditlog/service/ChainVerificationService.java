package com.kavin.auditlog.service;

import com.kavin.auditlog.domain.AuditEvent;
import com.kavin.auditlog.repository.AuditEventRepository;
import com.kavin.auditlog.web.dto.ChainVerificationResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ChainVerificationService {

    public enum Violation {
        PAYLOAD_FIELD_TAMPERED,
        CONTENT_HASH_MISMATCH,
        PREVIOUS_HASH_MISMATCH
    }

    private final AuditEventRepository repository;
    private final ChainHasher chainHasher;
    private final CanonicalJson canonicalJson;
    private final PayloadHasher payloadHasher;

    public ChainVerificationService(AuditEventRepository repository, ChainHasher chainHasher,
                                     CanonicalJson canonicalJson, PayloadHasher payloadHasher) {
        this.repository = repository;
        this.chainHasher = chainHasher;
        this.canonicalJson = canonicalJson;
        this.payloadHasher = payloadHasher;
    }

    /**
     * Walks the full chain in sequence order, including archived records (Scenario B retention):
     * archiving must not change stored hash fields, so archived rows still verify cleanly.
     * For each record:
     * <ol>
     *   <li>recombine the payload field-hash manifest (Scenario B redaction): for each field,
     *       either trust its preserved commitment (if redacted) or recompute and compare it to
     *       the stored commitment (if not) — catches tampering with any non-redacted field</li>
     *   <li>recompute contentHash from that manifest hash and the record's other fields, compare
     *       to the stored contentHash — catches tampering with the manifest itself, or with any
     *       of the record's non-payload fields</li>
     *   <li>compare stored previousHash to the prior record's contentHash (GENESIS_HASH for
     *       the first record) — catches deleted, reordered, or substituted records</li>
     * </ol>
     */
    @Transactional(readOnly = true)
    public ChainVerificationResponse verify() {
        List<AuditEvent> chain = repository.findAllByOrderBySequenceNumberAsc();

        String expectedPreviousHash = ChainHasher.GENESIS_HASH;
        long checked = 0;

        for (AuditEvent event : chain) {
            checked++;

            Map<String, String> manifest = parseManifest(event.getPayloadFieldHashes());
            Map<String, Object> livePayload = canonicalJson.parseToMap(event.getPayload());
            Set<String> redactedFields = Set.copyOf(canonicalJson.parseToStringList(event.getRedactedFields()));

            PayloadHasher.ManifestCheck manifestCheck =
                    payloadHasher.recombine(manifest, livePayload, redactedFields, event.getPayloadSalt());

            if (!manifestCheck.valid()) {
                return ChainVerificationResponse.broken(checked, event.getSequenceNumber(),
                        Violation.PAYLOAD_FIELD_TAMPERED.name(),
                        "payload field '" + manifestCheck.failedField() + "': " + manifestCheck.details());
            }

            String recomputedContentHash = chainHasher.hashContent(
                    event.getSequenceNumber(), event.getEventType(), event.getActorId(), event.getResourceType(),
                    event.getResourceId(), manifestCheck.manifestHash(), event.getOccurredAt(), event.getRecordedAt());

            if (!recomputedContentHash.equals(event.getContentHash())) {
                return ChainVerificationResponse.broken(checked, event.getSequenceNumber(),
                        Violation.CONTENT_HASH_MISMATCH.name(),
                        "stored content_hash does not match a hash recomputed from the record's current field values "
                                + "- the record (or its payload manifest) was modified after it was written");
            }

            if (!expectedPreviousHash.equals(event.getPreviousHash())) {
                return ChainVerificationResponse.broken(checked, event.getSequenceNumber(),
                        Violation.PREVIOUS_HASH_MISMATCH.name(),
                        "stored previous_hash does not match the preceding record's content_hash "
                                + "- a record was inserted, deleted, reordered, or substituted");
            }

            expectedPreviousHash = event.getContentHash();
        }

        return ChainVerificationResponse.intact(checked);
    }

    private Map<String, String> parseManifest(String json) {
        Map<String, Object> raw = canonicalJson.parseToMap(json);
        Map<String, String> manifest = new java.util.LinkedHashMap<>();
        raw.forEach((k, v) -> manifest.put(k, String.valueOf(v)));
        return manifest;
    }
}
