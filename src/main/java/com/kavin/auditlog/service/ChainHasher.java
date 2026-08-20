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
                + canonicalInstant(occurredAt) + FIELD_SEPARATOR
                + canonicalInstant(recordedAt);
        return sha256Hex(joined);
    }

    /**
     * {@code Instant.toString()} is not a safe hashing input: it's only used at write time
     * against the original in-memory object, but re-verification may recompute this hash from
     * an Instant that has round-tripped through JSON (bundle export/verify) or been reloaded
     * from the database - and different java.time serializers are not guaranteed to reproduce
     * an identical string for an equal instant (e.g. differing fractional-second truncation).
     * epochSecond+nano is what Instant.equals() actually compares, so encoding those two
     * numbers directly is the only representation guaranteed to match whenever the instants are
     * equal, regardless of how the value got there. Caught via ExportIntegrationTest failing
     * non-deterministically on unmodified data before this fix - see AI_USAGE_LOG.md.
     */
    private String canonicalInstant(Instant instant) {
        return instant.getEpochSecond() + "." + instant.getNano();
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
