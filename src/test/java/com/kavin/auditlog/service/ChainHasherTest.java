package com.kavin.auditlog.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ChainHasherTest {

    private final ChainHasher hasher = new ChainHasher();

    @Test
    void isDeterministicForSameInput() {
        Instant t = Instant.parse("2026-08-20T00:00:00Z");
        String h1 = hasher.hashContent(1L, "USER_LOGIN", "user-1", "SESSION", "sess-1", "{}", t, t);
        String h2 = hasher.hashContent(1L, "USER_LOGIN", "user-1", "SESSION", "sess-1", "{}", t, t);
        assertThat(h1).isEqualTo(h2);
        assertThat(h1).hasSize(64); // SHA-256 hex
    }

    @Test
    void changesWhenAnyFieldChanges() {
        Instant t = Instant.parse("2026-08-20T00:00:00Z");
        String base = hasher.hashContent(1L, "USER_LOGIN", "user-1", "SESSION", "sess-1", "{}", t, t);
        String differentActor = hasher.hashContent(1L, "USER_LOGIN", "user-2", "SESSION", "sess-1", "{}", t, t);
        assertThat(differentActor).isNotEqualTo(base);
    }

    @Test
    void fieldBoundariesAreNotAmbiguous() {
        Instant t = Instant.parse("2026-08-20T00:00:00Z");
        // "ab" + "c" must not hash the same as "a" + "bc" once concatenated across the actorId/resourceType boundary.
        String h1 = hasher.hashContent(1L, "TYPE", "ab", "c", "resource", "{}", t, t);
        String h2 = hasher.hashContent(1L, "TYPE", "a", "bc", "resource", "{}", t, t);
        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    void genesisHashIsSixtyFourZeroChars() {
        assertThat(ChainHasher.GENESIS_HASH).isEqualTo("0".repeat(64));
    }
}
