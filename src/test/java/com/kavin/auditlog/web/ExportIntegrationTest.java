package com.kavin.auditlog.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ExportIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:exporttest-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private void writeEvent(String resourceId, String field, Object value) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(field, value);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventType", "RECORD_UPDATED");
        body.put("actorId", "teller-1");
        body.put("resourceType", "ACCOUNT");
        body.put("resourceId", resourceId);
        body.put("payload", payload);
        ResponseEntity<Map> response = rest.postForEntity(baseUrl() + "/audit/events", body, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void exportedBundleForAResourceIsSelfVerifiable() {
        writeEvent("acct-1", "amount", 100);
        writeEvent("acct-2", "amount", 200); // different resource, should not appear in the bundle
        writeEvent("acct-1", "amount", 300);

        ResponseEntity<Map> bundleResponse = rest.getForEntity(
                baseUrl() + "/audit/export?resourceId=acct-1", Map.class);
        assertThat(bundleResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> bundle = bundleResponse.getBody();
        List<?> records = (List<?>) bundle.get("records");
        assertThat(records).hasSize(2);

        ResponseEntity<Map> verifyResponse = rest.postForEntity(
                baseUrl() + "/audit/export/verify", bundle, Map.class);
        assertThat(verifyResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(verifyResponse.getBody().get("intact")).isEqualTo(true);
        assertThat(verifyResponse.getBody().get("recordsChecked")).isEqualTo(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void tamperingWithTheBundleFileAfterExportIsDetected() {
        writeEvent("acct-5", "amount", 100);
        writeEvent("acct-5", "amount", 200);

        ResponseEntity<Map> bundleResponse = rest.getForEntity(
                baseUrl() + "/audit/export?resourceId=acct-5", Map.class);
        Map<String, Object> bundle = new LinkedHashMap<>(bundleResponse.getBody());

        // Tamper with the bundle file itself: edit a record's live payload after export,
        // exactly what an attacker modifying the JSON on disk (not the original DB) would do.
        List<Map<String, Object>> records = (List<Map<String, Object>>) bundle.get("records");
        Map<String, Object> firstRecord = new LinkedHashMap<>(records.get(0));
        Map<String, Object> payload = new LinkedHashMap<>((Map<String, Object>) firstRecord.get("payload"));
        payload.put("amount", 999999);
        firstRecord.put("payload", payload);
        records.set(0, firstRecord);

        ResponseEntity<Map> verifyResponse = rest.postForEntity(
                baseUrl() + "/audit/export/verify", bundle, Map.class);
        assertThat(verifyResponse.getBody().get("intact")).isEqualTo(false);
        assertThat(verifyResponse.getBody().get("violationType")).isEqualTo("PAYLOAD_FIELD_TAMPERED");
    }

    @Test
    void requiresExactlyOneFilter() {
        ResponseEntity<Map> noFilter = rest.getForEntity(baseUrl() + "/audit/export", Map.class);
        assertThat(noFilter.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<Map> bothFilters = rest.getForEntity(
                baseUrl() + "/audit/export?resourceId=x&actorId=y", Map.class);
        assertThat(bothFilters.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
