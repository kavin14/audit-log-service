package com.kavin.auditlog.service;

import com.kavin.auditlog.web.dto.ChainVerificationResponse;
import com.kavin.auditlog.web.dto.ExportBundle;
import com.kavin.auditlog.web.dto.ExportedRecord;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Re-verifies an {@link ExportBundle} using only the bundle itself - no database access, exactly
 * what an external recipient would do.
 *
 * <p><b>Important asymmetry with ChainVerificationService:</b> a bundle filtered by resourceId or
 * actorId is generally NOT a contiguous slice of the chain - other resources' records can sit
 * between two exported records' sequence numbers. So unlike the full-chain verifier, this cannot
 * check record[i].previousHash against record[i-1].contentHash for i&gt;0 - that would fail on
 * perfectly legitimate exports whenever an unrelated record happened to be appended in between.
 * The only chain-linkage check that generally holds is the *first* exported record's previousHash
 * against the bundle's declared previousHashBeforeBundle. (Caught by ExportIntegrationTest failing
 * on unmodified, legitimately-interleaved data before this fix - see AI_USAGE_LOG.md.)
 *
 * <p>What this verification does prove: each included record's own content is internally
 * consistent (recomputed contentHash matches, redaction-aware), and the bundle file as a whole
 * matches its own bundleHash (so nothing was added, removed, reordered, or altered since export).
 * What it does NOT prove: that the bundle is a *complete* record of everything that happened to
 * this resource (a malicious exporter could omit records without it being detectable from the
 * bundle alone), or that the bundle is authentic (see ExportService for why bundleHash is not a
 * substitute for a real signature).
 */
@Service
public class BundleVerificationService {

    private final ChainHasher chainHasher;
    private final CanonicalJson canonicalJson;
    private final PayloadHasher payloadHasher;

    public BundleVerificationService(ChainHasher chainHasher, CanonicalJson canonicalJson, PayloadHasher payloadHasher) {
        this.chainHasher = chainHasher;
        this.canonicalJson = canonicalJson;
        this.payloadHasher = payloadHasher;
    }

    public ChainVerificationResponse verify(ExportBundle bundle) {
        String recomputedBundleHash = recomputeBundleHash(bundle);
        if (!recomputedBundleHash.equals(bundle.bundleHash())) {
            return ChainVerificationResponse.broken(0, 0,
                    "BUNDLE_HASH_MISMATCH",
                    "the bundle's own bundleHash does not match its contents - the file was altered, "
                            + "truncated, or reordered since export");
        }

        long checked = 0;

        for (ExportedRecord record : bundle.records()) {
            checked++;

            PayloadHasher.ManifestCheck manifestCheck = payloadHasher.recombine(
                    record.payloadFieldHashes(), record.payload(), Set.copyOf(record.redactedFields()), record.payloadSalt());

            if (!manifestCheck.valid()) {
                return ChainVerificationResponse.broken(checked, record.sequenceNumber(),
                        "PAYLOAD_FIELD_TAMPERED",
                        "payload field '" + manifestCheck.failedField() + "': " + manifestCheck.details());
            }

            String recomputedContentHash = chainHasher.hashContent(record.sequenceNumber(), record.eventType(),
                    record.actorId(), record.resourceType(), record.resourceId(), manifestCheck.manifestHash(),
                    record.occurredAt(), record.recordedAt());

            if (!recomputedContentHash.equals(record.contentHash())) {
                return ChainVerificationResponse.broken(checked, record.sequenceNumber(),
                        "CONTENT_HASH_MISMATCH",
                        "stored content_hash does not match a hash recomputed from the record's field values");
            }

            // Chain-linkage is only checkable for the first record - see class javadoc for why
            // record[i].previousHash cannot generally be checked against record[i-1].contentHash
            // for a resource/actor-filtered (i.e. not necessarily contiguous) bundle.
            if (checked == 1 && !record.previousHash().equals(bundle.previousHashBeforeBundle())) {
                return ChainVerificationResponse.broken(checked, record.sequenceNumber(),
                        "PREVIOUS_HASH_MISMATCH",
                        "the first record's previous_hash does not match the bundle's declared "
                                + "previousHashBeforeBundle");
            }
        }

        return ChainVerificationResponse.intact(checked);
    }

    private String recomputeBundleHash(ExportBundle bundle) {
        List<Map<String, Object>> summary = bundle.records().stream()
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("sequenceNumber", r.sequenceNumber());
                    m.put("contentHash", r.contentHash());
                    return m;
                })
                .toList();

        Map<String, Object> forHashing = new LinkedHashMap<>();
        forHashing.put("exportedAt", bundle.exportedAt().getEpochSecond() + "." + bundle.exportedAt().getNano());
        forHashing.put("filter", bundle.filter());
        forHashing.put("previousHashBeforeBundle", bundle.previousHashBeforeBundle());
        forHashing.put("records", summary);

        return chainHasher.sha256Hex(canonicalJson.canonicalize(forHashing));
    }
}
