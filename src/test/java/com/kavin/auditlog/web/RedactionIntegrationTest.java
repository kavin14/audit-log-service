package com.kavin.auditlog.web;

import com.kavin.auditlog.domain.AuditEvent;
import com.kavin.auditlog.repository.AuditEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
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
class RedactionIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private AuditEventRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:redacttest-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    @Test
    void redactingAFieldKeepsTheChainIntactAndHidesTheValue() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("accountNumber", "9876543210");
        payload.put("action", "WITHDRAWAL");
        payload.put("amount", 500);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventType", "RECORD_UPDATED");
        body.put("actorId", "teller-1");
        body.put("resourceType", "ACCOUNT");
        body.put("resourceId", "acct-99");
        body.put("payload", payload);

        ResponseEntity<Map> created = rest.postForEntity(baseUrl() + "/audit/events", body, Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String eventId = (String) created.getBody().get("id");

        Map<String, Object> redactRequest = new LinkedHashMap<>();
        redactRequest.put("fields", List.of("accountNumber"));
        redactRequest.put("actorId", "compliance-officer-1");
        redactRequest.put("reason", "PCI scope reduction");

        ResponseEntity<Map> redacted = rest.postForEntity(
                baseUrl() + "/audit/events/" + eventId + "/redact", redactRequest, Map.class);
        assertThat(redacted.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> redactedPayload = (Map<String, Object>) redacted.getBody().get("payload");
        assertThat(redactedPayload.get("accountNumber")).isEqualTo("[REDACTED]");
        assertThat(redactedPayload.get("amount")).isEqualTo(500);
        List<String> redactedFields = (List<String>) redacted.getBody().get("redactedFields");
        assertThat(redactedFields).containsExactly("accountNumber");

        // The redaction itself is a chained event.
        ResponseEntity<Map> events = rest.getForEntity(
                baseUrl() + "/audit/events?eventType=PAYLOAD_REDACTED", Map.class);
        assertThat((List<?>) events.getBody().get("content")).hasSize(1);

        ResponseEntity<Map> verify = rest.getForEntity(baseUrl() + "/audit/verify", Map.class);
        assertThat(verify.getBody().get("intact")).isEqualTo(true);
    }

    @Test
    void tamperingWithANonRedactedFieldIsStillDetectedAfterAnUnrelatedRedaction() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ssn", "111-22-3333");
        payload.put("note", "routine check");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventType", "RECORD_UPDATED");
        body.put("actorId", "teller-2");
        body.put("resourceType", "ACCOUNT");
        body.put("resourceId", "acct-100");
        body.put("payload", payload);

        ResponseEntity<Map> created = rest.postForEntity(baseUrl() + "/audit/events", body, Map.class);
        String eventId = (String) created.getBody().get("id");

        Map<String, Object> redactRequest = new LinkedHashMap<>();
        redactRequest.put("fields", List.of("ssn"));
        redactRequest.put("actorId", "compliance-officer-1");
        rest.postForEntity(baseUrl() + "/audit/events/" + eventId + "/redact", redactRequest, Map.class);

        AuditEvent target = repository.findById(UUID.fromString(eventId)).orElseThrow();
        assertThat(target.getPayload()).contains("\"note\":\"routine check\"");

        // Tamper with the field that was NOT redacted, directly in the store.
        jdbcTemplate.update("UPDATE audit_event SET payload = REPLACE(payload, 'routine check', 'forged note') "
                + "WHERE id = ?", eventId);

        ResponseEntity<Map> verify = rest.getForEntity(baseUrl() + "/audit/verify", Map.class);
        assertThat(verify.getBody().get("intact")).isEqualTo(false);
        assertThat(verify.getBody().get("violationType")).isEqualTo("PAYLOAD_FIELD_TAMPERED");
    }

    @Test
    void redactingAnUnknownFieldIsRejected() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("field", "value");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventType", "RECORD_UPDATED");
        body.put("actorId", "teller-3");
        body.put("resourceType", "ACCOUNT");
        body.put("resourceId", "acct-101");
        body.put("payload", payload);

        ResponseEntity<Map> created = rest.postForEntity(baseUrl() + "/audit/events", body, Map.class);
        String eventId = (String) created.getBody().get("id");

        Map<String, Object> redactRequest = new LinkedHashMap<>();
        redactRequest.put("fields", List.of("doesNotExist"));
        redactRequest.put("actorId", "compliance-officer-1");

        ResponseEntity<Map> response = rest.postForEntity(
                baseUrl() + "/audit/events/" + eventId + "/redact", redactRequest, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
