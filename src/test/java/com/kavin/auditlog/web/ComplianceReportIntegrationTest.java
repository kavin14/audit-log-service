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
class ComplianceReportIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:compliancetest-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private void writeEvent(String eventType, String actorId, String resourceId, Map<String, Object> payload) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventType", eventType);
        body.put("actorId", actorId);
        body.put("resourceType", "ACCOUNT");
        body.put("resourceId", resourceId);
        body.put("payload", payload);
        ResponseEntity<Map> response = rest.postForEntity(baseUrl() + "/audit/events", body, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void jsonReportListsAccessEventsChronologicallyWithChainStatus() {
        writeEvent("ACCOUNT_VIEWED", "regulator-tooling", "acct-7", Map.of("viewedFields", "balance"));
        writeEvent("RECORD_UPDATED", "teller-1", "acct-7", Map.of("field", "balance", "newValue", 500));
        writeEvent("ACCOUNT_VIEWED", "auditor-2", "acct-8", Map.of("viewedFields", "balance")); // different account

        ResponseEntity<Map> response = rest.getForEntity(
                baseUrl() + "/audit/compliance-report?resourceId=acct-7", Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body.get("chainIntact")).isEqualTo(true);
        List<?> rows = (List<?>) body.get("rows");
        assertThat(rows).hasSize(2);
    }

    @Test
    void csvReportIsDownloadableWithHeaderRow() {
        writeEvent("ACCOUNT_VIEWED", "regulator-tooling", "acct-9", Map.of("viewedFields", "ssn"));

        ResponseEntity<String> response = rest.getForEntity(
                baseUrl() + "/audit/compliance-report?resourceId=acct-9&format=csv", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).contains("text/csv");
        assertThat(response.getHeaders().getFirst("Content-Disposition")).contains("attachment");
        String csv = response.getBody();
        assertThat(csv).contains("sequenceNumber,eventType,actorId,occurredAt,recordedAt,summary");
        assertThat(csv).contains("ACCOUNT_VIEWED");
        assertThat(csv).contains("regulator-tooling");
    }

    @Test
    void missingResourceIdIsRejected() {
        ResponseEntity<Map> response = rest.getForEntity(baseUrl() + "/audit/compliance-report", Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
