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
class RetentionIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:retentiontest-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private void writeEvent(String actorId, String resourceId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("note", "test event");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventType", "USER_LOGIN");
        body.put("actorId", actorId);
        body.put("resourceType", "SESSION");
        body.put("resourceId", resourceId);
        body.put("payload", payload);
        ResponseEntity<Map> response = rest.postForEntity(baseUrl() + "/audit/events", body, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void archivingDoesNotBreakChainVerificationOrHideRecordsFromExplicitQueries() {
        writeEvent("alice", "sess-1");
        writeEvent("alice", "sess-2");
        writeEvent("alice", "sess-3");

        // Archive everything by using an olderThanDays of 0 - every record is "older than now".
        ResponseEntity<Map> archiveResponse = rest.postForEntity(
                baseUrl() + "/audit/retention/archive?olderThanDays=0", null, Map.class);
        assertThat(archiveResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(archiveResponse.getBody().get("archivedCount")).isEqualTo(3);

        ResponseEntity<Map> verify = rest.getForEntity(baseUrl() + "/audit/verify", Map.class);
        assertThat(verify.getBody().get("intact")).isEqualTo(true);
        assertThat(verify.getBody().get("recordsChecked")).isEqualTo(3);

        ResponseEntity<Map> defaultQuery = rest.getForEntity(
                baseUrl() + "/audit/events?actorId=alice", Map.class);
        assertThat((List<?>) defaultQuery.getBody().get("content")).isEmpty();

        ResponseEntity<Map> includeArchivedQuery = rest.getForEntity(
                baseUrl() + "/audit/events?actorId=alice&includeArchived=true", Map.class);
        assertThat((List<?>) includeArchivedQuery.getBody().get("content")).hasSize(3);
    }

    @Test
    void defaultWindowDoesNotArchiveFreshRecords() {
        writeEvent("bob", "sess-9");

        ResponseEntity<Map> archiveResponse = rest.postForEntity(
                baseUrl() + "/audit/retention/archive", null, Map.class);
        assertThat(archiveResponse.getBody().get("archivedCount")).isEqualTo(0);
        assertThat(archiveResponse.getBody().get("windowDays")).isEqualTo(90);

        ResponseEntity<Map> query = rest.getForEntity(baseUrl() + "/audit/events?actorId=bob", Map.class);
        assertThat((List<?>) query.getBody().get("content")).hasSize(1);
    }
}
