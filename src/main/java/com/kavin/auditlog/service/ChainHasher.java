package com.kavin.auditlog.service;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;

/**
 * Computes the per-record content hash used to build the tamper-evident chain.
 *
 * <p>Fields are joined with the ASCII SOH control character (0x01) as a separator —
 * a byte that cannot appear in any of the field values here (canonical JSON payload
 * text can contain 0x01 only inside a string, and canonical JSON escapes control
 * characters below 0x20, so 0x01 never appears literally in the payload segment
 * either). This rules out field-boundary ambiguity (e.g. actorId="a" + resourceId="bc"
 * hashing the same as actorId="ab" + resourceId="c") without needing a full
 * canonical-JSON envelope for the whole record.
 */
@Component
public class ChainHasher {

    /** previousHash of the first record in the chain — there is no real predecessor to point to. */
    public static final String GENESIS_HASH = "0".repeat(64);

    private static final char FIELD_SEPARATOR = '';

    public String hashContent(Long sequenceNumber, String eventType, String actorId, String resourceType,
                               String resourceId, String canonicalPayload, Instant occurredAt, Instant recordedAt) {
        String joined = String.valueOf(sequenceNumber) + FIELD_SEPARATOR
                + eventType + FIELD_SEPARATOR
                + actorId + FIELD_SEPARATOR
                + resourceType + FIELD_SEPARATOR
                + resourceId + FIELD_SEPARATOR
                + canonicalPayload + FIELD_SEPARATOR
                + occurredAt + FIELD_SEPARATOR
                + recordedAt;
        return sha256Hex(joined);
    }

    public String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed present on every JVM implementation; this is unreachable in practice.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
