package com.kavin.auditlog.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Builds and recombines the per-field hash manifest that makes redaction possible without
 * invalidating the chain (Scenario B). See ARCHITECTURE.md "Redaction" for the full design
 * rationale; summary:
 *
 * <p>Instead of hashing the payload as one opaque blob (as Scenario A did), each top-level
 * payload field gets its own salted commitment hash. The manifest of per-field hashes is what
 * actually feeds the record's contentHash — never the raw payload text. A field can later be
 * redacted (its live value replaced with a placeholder) without touching the manifest or the
 * contentHash, because the manifest already captured that field's original value as a hash, not
 * as plaintext. Verifying a redacted field means trusting its preserved commitment instead of
 * recomputing it; verifying a non-redacted field means recomputing and checking it still matches.
 */
@Component
public class PayloadHasher {

    private final CanonicalJson canonicalJson;
    private final ChainHasher chainHasher;
    private final SecureRandom random = new SecureRandom();

    public PayloadHasher(CanonicalJson canonicalJson, ChainHasher chainHasher) {
        this.canonicalJson = canonicalJson;
        this.chainHasher = chainHasher;
    }

    public String newSalt() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** One field's commitment: salt + field name + canonical JSON of its value, SHA-256'd. */
    public String fieldCommitment(String salt, String fieldName, Object fieldValue) {
        String valueJson = canonicalJson.canonicalizeValue(fieldValue);
        return chainHasher.sha256Hex(salt + '' + fieldName + '' + valueJson);
    }

    /** Builds the full per-field manifest (sorted by field name) from a payload map. */
    public Map<String, String> buildManifest(Map<String, Object> payload, String salt) {
        Map<String, String> manifest = new TreeMap<>();
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            manifest.put(entry.getKey(), fieldCommitment(salt, entry.getKey(), entry.getValue()));
        }
        return new LinkedHashMap<>(manifest);
    }

    /** The single hash that feeds the record's contentHash - the "root" over the field manifest. */
    public String manifestHash(Map<String, String> manifest) {
        return chainHasher.sha256Hex(canonicalJson.canonicalize(manifest));
    }

    /**
     * Recombines the manifest hash for verification: for each field the stored manifest knows
     * about, either trust its preserved commitment (if the field is redacted) or recompute the
     * commitment from the record's current live value and require it to match the stored one.
     *
     * @return the recombined manifest hash, or empty if a non-redacted field's live value no
     *         longer matches its stored commitment (the caller should report exactly which field).
     */
    public ManifestCheck recombine(Map<String, String> storedManifest, Map<String, Object> livePayload,
                                    Set<String> redactedFields, String salt) {
        Map<String, String> reconciled = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : storedManifest.entrySet()) {
            String field = entry.getKey();
            String storedCommitment = entry.getValue();
            if (redactedFields.contains(field)) {
                reconciled.put(field, storedCommitment);
                continue;
            }
            if (!livePayload.containsKey(field)) {
                return ManifestCheck.failed(field, "field present in the original manifest is missing "
                        + "from the current payload and is not marked as redacted");
            }
            String recomputed = fieldCommitment(salt, field, livePayload.get(field));
            if (!recomputed.equals(storedCommitment)) {
                return ManifestCheck.failed(field, "field value no longer matches its original commitment "
                        + "hash and the field is not marked as redacted");
            }
            reconciled.put(field, recomputed);
        }
        return ManifestCheck.ok(manifestHash(reconciled));
    }

    public record ManifestCheck(boolean valid, String manifestHash, String failedField, String details) {
        static ManifestCheck ok(String manifestHash) {
            return new ManifestCheck(true, manifestHash, null, null);
        }

        static ManifestCheck failed(String field, String details) {
            return new ManifestCheck(false, null, field, details);
        }
    }
}
