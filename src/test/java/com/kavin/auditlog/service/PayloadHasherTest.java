package com.kavin.auditlog.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PayloadHasherTest {

    private final CanonicalJson canonicalJson = new CanonicalJson();
    private final ChainHasher chainHasher = new ChainHasher();
    private final PayloadHasher hasher = new PayloadHasher(canonicalJson, chainHasher);

    private Map<String, Object> payload() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("accountNumber", "123456789");
        p.put("field", "balance");
        p.put("newValue", 250);
        return p;
    }

    @Test
    void recombiningAnUnredactedManifestReproducesTheOriginalManifestHash() {
        String salt = hasher.newSalt();
        Map<String, String> manifest = hasher.buildManifest(payload(), salt);
        String originalHash = hasher.manifestHash(manifest);

        PayloadHasher.ManifestCheck check = hasher.recombine(manifest, payload(), Set.of(), salt);

        assertThat(check.valid()).isTrue();
        assertThat(check.manifestHash()).isEqualTo(originalHash);
    }

    @Test
    void redactingAFieldStillReproducesTheOriginalManifestHash() {
        String salt = hasher.newSalt();
        Map<String, Object> original = payload();
        Map<String, String> manifest = hasher.buildManifest(original, salt);
        String originalHash = hasher.manifestHash(manifest);

        Map<String, Object> redactedPayload = new LinkedHashMap<>(original);
        redactedPayload.put("accountNumber", "[REDACTED]");

        PayloadHasher.ManifestCheck check = hasher.recombine(manifest, redactedPayload, Set.of("accountNumber"), salt);

        assertThat(check.valid()).isTrue();
        assertThat(check.manifestHash()).isEqualTo(originalHash);
    }

    @Test
    void tamperingWithANonRedactedFieldIsDetected() {
        String salt = hasher.newSalt();
        Map<String, Object> original = payload();
        Map<String, String> manifest = hasher.buildManifest(original, salt);

        Map<String, Object> tampered = new LinkedHashMap<>(original);
        tampered.put("newValue", 999999);

        PayloadHasher.ManifestCheck check = hasher.recombine(manifest, tampered, Set.of(), salt);

        assertThat(check.valid()).isFalse();
        assertThat(check.failedField()).isEqualTo("newValue");
    }

    @Test
    void tamperingWithARedactedFieldsPlaceholderIsNotFlaggedByFieldCheck() {
        // Once a field is redacted, its live value is no longer independently verifiable -
        // this is the documented trade-off. What DOES still protect it is that the manifest
        // itself (used here) is immutable and chained into contentHash; a manifest edit would
        // change the recombined hash and be caught one level up, in ChainVerificationService.
        String salt = hasher.newSalt();
        Map<String, Object> original = payload();
        Map<String, String> manifest = hasher.buildManifest(original, salt);
        String originalHash = hasher.manifestHash(manifest);

        Map<String, Object> redactedPayload = new LinkedHashMap<>(original);
        redactedPayload.put("accountNumber", "anything at all");

        PayloadHasher.ManifestCheck check = hasher.recombine(manifest, redactedPayload, Set.of("accountNumber"), salt);

        assertThat(check.valid()).isTrue();
        assertThat(check.manifestHash()).isEqualTo(originalHash);
    }

    @Test
    void differentSaltsProduceDifferentCommitmentsForTheSameValue() {
        String hash1 = hasher.fieldCommitment("salt-a", "accountNumber", "123456789");
        String hash2 = hasher.fieldCommitment("salt-b", "accountNumber", "123456789");
        assertThat(hash1).isNotEqualTo(hash2);
    }
}
