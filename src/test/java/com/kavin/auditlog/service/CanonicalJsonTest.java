package com.kavin.auditlog.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalJsonTest {

    private final CanonicalJson canonicalJson = new CanonicalJson();

    @Test
    void sortsTopLevelKeysRegardlessOfInputOrder() {
        String a = canonicalJson.canonicalize("{\"b\":1,\"a\":2}");
        String b = canonicalJson.canonicalize("{\"a\":2,\"b\":1}");
        assertThat(a).isEqualTo(b);
        assertThat(a).isEqualTo("{\"a\":2,\"b\":1}");
    }

    @Test
    void sortsNestedObjectKeysRecursively() {
        String a = canonicalJson.canonicalize("{\"outer\":{\"z\":1,\"y\":2}}");
        String b = canonicalJson.canonicalize("{\"outer\":{\"y\":2,\"z\":1}}");
        assertThat(a).isEqualTo(b);
    }

    @Test
    void preservesArrayOrder() {
        String a = canonicalJson.canonicalize("{\"list\":[3,1,2]}");
        assertThat(a).isEqualTo("{\"list\":[3,1,2]}");
    }

    @Test
    void rejectsNonObjectPayload() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> canonicalJson.canonicalize("[1,2,3]"));
    }
}
