package com.kavin.auditlog.service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Produces a deterministic JSON serialization of a payload map: object keys sorted
 * recursively, array order preserved. Used so that hashing gives the same result
 * whether the JSON arrived with keys in a different order or after a DB round-trip.
 *
 * <p>Spring Boot 4 ships Jackson 3 ({@code tools.jackson.*}), not the older Jackson 2
 * ({@code com.fasterxml.jackson.databind.*}) most examples online still reference —
 * {@code ORDER_MAP_ENTRIES_BY_KEYS} in particular moved from {@code MapperFeature} to
 * {@code SerializationFeature}.
 */
@Component
public class CanonicalJson {

    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    /** Parses arbitrary JSON text and re-serializes it with sorted keys. */
    public String canonicalize(String json) {
        try {
            Map<String, Object> parsed = mapper.readValue(json, LinkedHashMap.class);
            return mapper.writeValueAsString(parsed);
        } catch (Exception e) {
            throw new IllegalArgumentException("payload must be a JSON object: " + e.getMessage(), e);
        }
    }

    /** Serializes an already-in-memory map with sorted keys (used for request DTOs). */
    public String canonicalize(Map<String, ?> value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("payload could not be serialized: " + e.getMessage(), e);
        }
    }

    /** Parses stored canonical JSON back into a map for API responses. */
    public Map<String, Object> parseToMap(String json) {
        return mapper.readValue(json, LinkedHashMap.class);
    }

    /** Parses a stored canonical JSON array back into a list (used for redactedFields). */
    public List<String> parseToStringList(String json) {
        return mapper.readValue(json, List.class);
    }

    public String canonicalizeList(List<String> value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("value could not be serialized: " + e.getMessage(), e);
        }
    }

    /** Canonical JSON for a single arbitrary value (used to hash one payload field in isolation). */
    public String canonicalizeValue(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("value could not be serialized: " + e.getMessage(), e);
        }
    }
}
