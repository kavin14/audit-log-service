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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the service end-to-end the same way the assignment says it will be validated:
 * write events via the API, query them, verify the chain, then modify a record directly
 * in the data store and verify again to confirm tampering is detected.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
class AuditLogApiIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private AuditEventRepository repository;

    // Unique DB file per test run so tests don't collect state across runs (see application-test.properties).
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:audittest-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private Map<String, Object> eventBody(String actorId, String resourceId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventType", "RECORD_UPDATED");
        body.put("actorId", actorId);
        body.put("resourceType", "ACCOUNT");
        body.put("resourceId", resourceId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("field", "balance");
        payload.put("newValue", 100);
        body.put("payload", payload);
        return body;
    }

    @Test
    void writeQueryAndVerifyHappyPath() {
        for (int i = 0; i < 3; i++) {
            ResponseEntity<Map> created = rest.postForEntity(baseUrl() + "/audit/events",
                    eventBody("actor-1", "acct-" + i), Map.class);
            assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        ResponseEntity<Map> queried = rest.getForEntity(
                baseUrl() + "/audit/events?actorId=actor-1&size=10", Map.class);
        assertThat(queried.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((java.util.List<?>) queried.getBody().get("content")).hasSize(3);

        ResponseEntity<Map> verify = rest.getForEntity(baseUrl() + "/audit/verify", Map.class);
        assertThat(verify.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(verify.getBody().get("intact")).isEqualTo(true);
    }

    @Test
    void rejectsInvalidRequest() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventType", "");
        ResponseEntity<Map> response = rest.postForEntity(baseUrl() + "/audit/events", body, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void detectsDirectTamperingWithAStoredRecord() {
        rest.postForEntity(baseUrl() + "/audit/events", eventBody("actor-2", "acct-x"), Map.class);
        rest.postForEntity(baseUrl() + "/audit/events", eventBody("actor-2", "acct-y"), Map.class);
        rest.postForEntity(baseUrl() + "/audit/events", eventBody("actor-2", "acct-z"), Map.class);

        ResponseEntity<Map> before = rest.getForEntity(baseUrl() + "/audit/verify", Map.class);
        assertThat(before.getBody().get("intact")).isEqualTo(true);

        // Modify a past record directly in the data store, bypassing the write API entirely.
        // The entity exposes no setter for these fields, so we go around it with a raw SQL
        // update - simulating an operator or attacker editing the database out-of-band.
        AuditEvent target = repository.findAllByOrderBySequenceNumberAsc().get(1);
        tamperActorIdDirectlyInDatabase(target.getId());

        ResponseEntity<Map> after = rest.getForEntity(baseUrl() + "/audit/verify", Map.class);
        assertThat(after.getBody().get("intact")).isEqualTo(false);
        assertThat(after.getBody().get("violationType")).isEqualTo("CONTENT_HASH_MISMATCH");
        assertThat(after.getBody().get("firstBrokenSequenceNumber")).isEqualTo(target.getSequenceNumber().intValue());
    }

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private void tamperActorIdDirectlyInDatabase(UUID id) {
        int updated = jdbcTemplate.update("UPDATE audit_event SET actor_id = ? WHERE id = ?", "attacker", id.toString());
        assertThat(updated).isEqualTo(1);
    }
}
