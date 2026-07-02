package com.buildgraph.prototype.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.common.ApiException;
import com.buildgraph.prototype.config.security.AgentPrincipal;
import com.buildgraph.prototype.config.security.AgentTokenHasher;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

class PcAgentAsServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-02T00:00:00Z"), ZoneOffset.UTC);
    private static final AgentPrincipal AGENT = new AgentPrincipal(10L, "device-public-id", 20L, "ACTIVE");

    private final JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
    private final AgentTokenHasher tokenHasher = new AgentTokenHasher();
    private final PcAgentAsService service = new PcAgentAsService(
            jdbcTemplate,
            tokenHasher,
            CLOCK,
            () -> "raw-agent-token"
    );

    @Test
    void springCanInstantiateServiceWithProductionConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(JdbcTemplate.class, () -> jdbcTemplate);
            context.registerBean(AgentTokenHasher.class, () -> tokenHasher);
            context.register(PcAgentAsService.class);
            context.refresh();

            assertThat(context.getBean(PcAgentAsService.class)).isNotNull();
        }
    }

    @Test
    void registerStoresHashedAgentTokenAndReturnsRawTokenOnce() {
        String tokenHash = tokenHasher.sha256Hex("raw-agent-token");
        when(jdbcTemplate.queryForList(contains("FROM agent_devices"), eq("user@example.com"), eq("register-1")))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForMap(
                contains("agent_token_hash"),
                eq("user@example.com"),
                eq("fingerprint-hash"),
                eq("host-hash"),
                eq(tokenHash),
                eq("register-1"),
                eq("Windows 11"),
                eq("0.1.0"),
                eq("policy-v1")
        )).thenReturn(MockData.map(
                "device_internal_id", 10L,
                "device_id", "device-public-id",
                "status", "ACTIVE"
        ));

        Map<String, Object> response = service.register(MockData.map(
                "activationToken", "demo-agent-activation-token",
                "deviceFingerprintHash", "fingerprint-hash",
                "hostnameHash", "host-hash",
                "registrationIdempotencyKey", "register-1",
                "osVersion", "Windows 11",
                "agentVersion", "0.1.0",
                "policyVersion", "policy-v1"
        ));

        assertThat(response.get("agentToken")).isEqualTo("raw-agent-token");
        assertThat(response.get("deviceId")).isEqualTo("device-public-id");
        assertThat(response).containsOnlyKeys("deviceId", "status", "agentToken", "tokenType");
        assertThat(response.get("tokenType")).isEqualTo("Bearer");
        assertThat(tokenHash).isNotEqualTo("raw-agent-token");
    }

    @Test
    void registerRejectsMissingActivationTokenBeforeIssuingToken() {
        PcAgentAsService guardedService = new PcAgentAsService(
                jdbcTemplate,
                tokenHasher,
                CLOCK,
                () -> {
                    throw new AssertionError("token must not be generated when activationToken is missing");
                }
        );

        assertThatThrownBy(() -> guardedService.register(MockData.map(
                "deviceFingerprintHash", "fingerprint-hash",
                "registrationIdempotencyKey", "register-1",
                "osVersion", "Windows 11",
                "agentVersion", "0.1.0",
                "policyVersion", "policy-v1"
        )))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void registerRejectsMissingRequiredFieldsBeforeIssuingToken() {
        assertThatThrownBy(() -> service.register(MockData.map(
                "activationToken", "demo-agent-activation-token",
                "osVersion", "Windows 11",
                "agentVersion", "0.1.0",
                "policyVersion", "policy-v1"
        )))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void registerRejectsInvalidActivationToken() {
        assertThatThrownBy(() -> service.register(MockData.map(
                "activationToken", "invalid-token",
                "deviceFingerprintHash", "fingerprint-hash",
                "registrationIdempotencyKey", "register-1",
                "osVersion", "Windows 11",
                "agentVersion", "0.1.0",
                "policyVersion", "policy-v1"
        )))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void registerRefreshesExistingDeviceForSameRegistrationKeyWithoutRawTokenStorage() {
        String tokenHash = tokenHasher.sha256Hex("raw-agent-token");
        when(jdbcTemplate.queryForList(contains("FROM agent_devices"), eq("user@example.com"), eq("register-1")))
                .thenReturn(List.of(MockData.map(
                        "device_internal_id", 10L,
                        "device_id", "device-public-id",
                        "status", "ACTIVE"
                )));
        when(jdbcTemplate.queryForMap(
                contains("UPDATE agent_devices"),
                eq("fingerprint-hash"),
                eq("host-hash"),
                eq(tokenHash),
                eq("Windows 11"),
                eq("0.1.1"),
                eq("policy-v2"),
                eq(10L)
        )).thenReturn(MockData.map(
                "device_internal_id", 10L,
                "device_id", "device-public-id",
                "status", "ACTIVE"
        ));

        Map<String, Object> response = service.register(MockData.map(
                "activationToken", "demo-agent-activation-token",
                "deviceFingerprintHash", "fingerprint-hash",
                "hostnameHash", "host-hash",
                "registrationIdempotencyKey", "register-1",
                "osVersion", "Windows 11",
                "agentVersion", "0.1.1",
                "policyVersion", "policy-v2"
        ));

        assertThat(response.get("deviceId")).isEqualTo("device-public-id");
        assertThat(response.get("agentToken")).isEqualTo("raw-agent-token");
        assertThat(tokenHash).isNotEqualTo("raw-agent-token");
    }

    @Test
    void saveConsentStoresExplicitRevokeState() {
        when(jdbcTemplate.queryForMap(
                contains("INSERT INTO agent_consents"),
                eq(20L),
                eq(10L),
                eq("SERVER_UPLOAD"),
                eq("policy-v1"),
                eq("consent-key"),
                eq(false),
                eq(false),
                eq(false)
        )).thenReturn(MockData.map(
                "id", "consent-public-id",
                "consent_type", "SERVER_UPLOAD",
                "policy_version", "policy-v1",
                "accepted", false,
                "accepted_at", null,
                "revoked_at", Instant.parse("2026-07-02T00:00:00Z")
        ));

        Map<String, Object> response = service.saveConsent(
                AGENT,
                MockData.map(
                        "consentType", "SERVER_UPLOAD",
                        "policyVersion", "policy-v1",
                        "accepted", false
                ),
                "consent-key"
        );

        assertThat(response.get("accepted")).isEqualTo(false);
        assertThat(response.get("revokedAt")).isEqualTo(Instant.parse("2026-07-02T00:00:00Z"));
    }

    @Test
    void saveConsentRejectsUnknownConsentType() {
        assertThatThrownBy(() -> service.saveConsent(
                AGENT,
                MockData.map(
                        "consentType", "REMOTE_CONTROL",
                        "policyVersion", "policy-v1",
                        "accepted", true
                ),
                "consent-key"
        ))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void saveConsentRejectsMissingAcceptedFlag() {
        assertThatThrownBy(() -> service.saveConsent(
                AGENT,
                MockData.map(
                        "consentType", "SERVER_UPLOAD",
                        "policyVersion", "policy-v1"
                ),
                "consent-key"
        ))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void heartbeatUpdatesDeviceLastSeenAndStoresHeartbeat() {
        Instant seenAt = Instant.parse("2026-07-02T00:00:00Z");
        when(jdbcTemplate.queryForMap(
                contains("UPDATE agent_devices"),
                eq("0.1.1"),
                eq("policy-v2"),
                eq(10L),
                eq(20L)
        )).thenReturn(MockData.map(
                "status", "ACTIVE",
                "last_seen_at", seenAt
        ));
        when(jdbcTemplate.queryForMap(
                contains("INSERT INTO agent_heartbeats"),
                eq(10L),
                eq("0.1.1"),
                eq("RUNNING"),
                eq("VISIBLE"),
                eq("policy-v2"),
                eq("heartbeat-key")
        )).thenReturn(MockData.map(
                "id", "heartbeat-public-id",
                "received_at", seenAt
        ));

        Map<String, Object> response = service.heartbeat(
                AGENT,
                MockData.map(
                        "agentVersion", "0.1.1",
                        "serviceStatus", "RUNNING",
                        "trayStatus", "VISIBLE",
                        "policyVersion", "policy-v2"
                ),
                "heartbeat-key"
        );

        assertThat(response.get("deviceId")).isEqualTo("device-public-id");
        assertThat(response.get("status")).isEqualTo("ACTIVE");
        assertThat(response.get("lastSeenAt")).isEqualTo(seenAt);
        assertThat(response).containsOnlyKeys(
                "id",
                "deviceId",
                "status",
                "lastSeenAt",
                "receivedAt",
                "pendingCommands"
        );
    }

    @Test
    void repeatedHeartbeatUpdatesDeviceAndStoresSeparateHeartbeatRows() {
        Instant firstSeenAt = Instant.parse("2026-07-02T00:00:00Z");
        Instant secondSeenAt = Instant.parse("2026-07-02T00:00:05Z");
        when(jdbcTemplate.queryForMap(
                contains("UPDATE agent_devices"),
                eq("0.1.1"),
                eq("policy-v2"),
                eq(10L),
                eq(20L)
        )).thenReturn(
                MockData.map("status", "ACTIVE", "last_seen_at", firstSeenAt),
                MockData.map("status", "ACTIVE", "last_seen_at", secondSeenAt)
        );
        when(jdbcTemplate.queryForMap(
                contains("INSERT INTO agent_heartbeats"),
                eq(10L),
                eq("0.1.1"),
                eq("RUNNING"),
                eq("VISIBLE"),
                eq("policy-v2"),
                eq("heartbeat-key")
        )).thenReturn(
                MockData.map("id", "heartbeat-public-id-1", "received_at", firstSeenAt),
                MockData.map("id", "heartbeat-public-id-2", "received_at", secondSeenAt)
        );

        Map<String, Object> firstResponse = service.heartbeat(
                AGENT,
                MockData.map(
                        "agentVersion", "0.1.1",
                        "serviceStatus", "RUNNING",
                        "trayStatus", "VISIBLE",
                        "policyVersion", "policy-v2"
                ),
                "heartbeat-key"
        );
        Map<String, Object> secondResponse = service.heartbeat(
                AGENT,
                MockData.map(
                        "agentVersion", "0.1.1",
                        "serviceStatus", "RUNNING",
                        "trayStatus", "VISIBLE",
                        "policyVersion", "policy-v2"
                ),
                "heartbeat-key"
        );

        assertThat(firstResponse.get("lastSeenAt")).isEqualTo(firstSeenAt);
        assertThat(secondResponse.get("lastSeenAt")).isEqualTo(secondSeenAt);
        verify(jdbcTemplate, times(2)).queryForMap(
                contains("UPDATE agent_devices"),
                eq("0.1.1"),
                eq("policy-v2"),
                eq(10L),
                eq(20L)
        );
        verify(jdbcTemplate, times(2)).queryForMap(
                contains("INSERT INTO agent_heartbeats"),
                eq(10L),
                eq("0.1.1"),
                eq("RUNNING"),
                eq("VISIBLE"),
                eq("policy-v2"),
                eq("heartbeat-key")
        );
    }

    @Test
    void uploadLogsCreatesUploadJobLogUploadAndTicketWithDiagnosisStatus() {
        when(jdbcTemplate.queryForObject(contains("FROM agent_consents"), eq(Integer.class), eq(10L)))
                .thenReturn(1);
        when(jdbcTemplate.queryForMap(contains("INSERT INTO agent_upload_jobs"), eq(10L), eq("upload-key"), any(), any()))
                .thenReturn(MockData.map(
                        "upload_job_internal_id", 100L,
                        "upload_job_id", "upload-job-public-id",
                        "status", "UPLOADED"
                ));
        when(jdbcTemplate.queryForMap(
                contains("INSERT INTO agent_log_uploads"),
                eq(20L),
                eq(10L),
                eq(100L),
                eq(30),
                eq("agent-log.jsonl.gz"),
                any(Long.class),
                eq("agent-logs/device-public-id/agent-log.jsonl.gz")
        )).thenReturn(MockData.map(
                "log_upload_internal_id", 200L,
                "log_upload_id", "log-upload-public-id",
                "status", "UPLOADED",
                "file_name", "agent-log.jsonl.gz",
                "file_size", 65L,
                "range_minutes", 30,
                "delete_after", Instant.parse("2026-08-01T00:00:00Z")
        ));
        when(jdbcTemplate.queryForMap(
                contains("INSERT INTO agent_log_bundles"),
                eq(100L),
                eq(200L),
                eq(1),
                eq("agent-logs/device-public-id/agent-log.jsonl.gz"),
                any(String.class),
                any(Long.class),
                eq(Instant.parse("2026-08-01T00:00:00Z"))
        )).thenReturn(MockData.map("log_bundle_id", "bundle-public-id"));
        when(jdbcTemplate.queryForMap(
                contains("INSERT INTO as_tickets"),
                eq(20L),
                eq(200L),
                eq("GPU temperature spike")
        )).thenReturn(MockData.map(
                "ticket_id", "ticket-public-id",
                "status", "OPEN",
                "analysis_status", "RULE_READY",
                "review_status", "REQUIRED",
                "support_decision", "NEEDS_MORE_INFO"
        ));

        Map<String, Object> response = service.uploadLogs(
                AGENT,
                new MockMultipartFile("file", "agent-log.jsonl.gz", "application/gzip", gzip("demo log\n")),
                MockData.map("rangeMinutes", 30, "symptom", "GPU temperature spike"),
                "upload-key"
        );

        assertThat(response.get("uploadJobId")).isEqualTo("upload-job-public-id");
        assertThat(response.get("logUploadId")).isEqualTo("log-upload-public-id");
        assertThat(response.get("ticketId")).isEqualTo("ticket-public-id");
        assertThat(response.get("analysisStatus")).isEqualTo("RULE_READY");
        assertThat(response.get("reviewStatus")).isEqualTo("REQUIRED");
        assertThat(response.get("supportDecision")).isEqualTo("NEEDS_MORE_INFO");
        assertThat(response.get("rangeMinutes")).isEqualTo(30);

        verify(jdbcTemplate).queryForMap(contains("INSERT INTO agent_upload_jobs"), eq(10L), eq("upload-key"), any(), any());
        verify(jdbcTemplate).queryForMap(
                contains("INSERT INTO agent_log_uploads"),
                eq(20L),
                eq(10L),
                eq(100L),
                eq(30),
                eq("agent-log.jsonl.gz"),
                any(Long.class),
                eq("agent-logs/device-public-id/agent-log.jsonl.gz")
        );
        verify(jdbcTemplate).queryForMap(
                contains("INSERT INTO as_tickets"),
                eq(20L),
                eq(200L),
                eq("GPU temperature spike")
        );
    }

    @Test
    void uploadLogsRejectsMissingServerUploadConsent() {
        when(jdbcTemplate.queryForObject(contains("FROM agent_consents"), eq(Integer.class), eq(10L)))
                .thenReturn(0);

        assertThatThrownBy(() -> service.uploadLogs(
                AGENT,
                new MockMultipartFile("file", "agent-log.jsonl.gz", "application/gzip", gzip("demo log\n")),
                MockData.map("rangeMinutes", 30),
                "upload-key"
        ))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void uploadLogsRejectsInvalidGzipBeforeCreatingRows() {
        assertThatThrownBy(() -> service.uploadLogs(
                AGENT,
                new MockMultipartFile("file", "agent-log.jsonl.gz", "application/gzip", "not-gzip".getBytes()),
                MockData.map("rangeMinutes", 30),
                "upload-key"
        ))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> {
                    ApiException apiException = (ApiException) exception;
                    assertThat(apiException.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(apiException.code()).isEqualTo("FILE_VALIDATION_ERROR");
                });
    }

    @Test
    void uploadLogsRejectsMissingRangeMinutesBeforeCreatingTicket() {
        assertThatThrownBy(() -> service.uploadLogs(
                AGENT,
                new MockMultipartFile("file", "agent-log.jsonl.gz", "application/gzip", gzip("demo log\n")),
                MockData.map("symptom", "GPU temperature spike"),
                "upload-key"
        ))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));

        verify(jdbcTemplate, never()).queryForMap(contains("INSERT INTO as_tickets"), any(), any(), any());
    }

    @Test
    void uploadLogsRejectsNonThirtyMinuteRange() {
        assertThatThrownBy(() -> service.uploadLogs(
                AGENT,
                new MockMultipartFile("file", "agent-log.jsonl.gz", "application/gzip", gzip("demo log\n")),
                MockData.map("rangeMinutes", 45),
                "upload-key"
        ))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void uploadLogsRejectsNonGzipExtensionBeforeCreatingTicket() {
        assertThatThrownBy(() -> service.uploadLogs(
                AGENT,
                new MockMultipartFile("file", "agent-log.jsonl", "application/json", gzip("demo log\n")),
                MockData.map("rangeMinutes", 30),
                "upload-key"
        ))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("FILE_VALIDATION_ERROR"));

        verify(jdbcTemplate, never()).queryForMap(contains("INSERT INTO as_tickets"), any(), any(), any());
    }

    @Test
    void uploadLogsRejectsEmptyGzipContentBeforeCreatingTicket() {
        assertThatThrownBy(() -> service.uploadLogs(
                AGENT,
                new MockMultipartFile("file", "agent-log.jsonl.gz", "application/gzip", gzip("")),
                MockData.map("rangeMinutes", 30),
                "upload-key"
        ))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("FILE_VALIDATION_ERROR"));

        verify(jdbcTemplate, never()).queryForMap(contains("INSERT INTO as_tickets"), any(), any(), any());
    }

    @Test
    void uploadLogsRejectsMissingConsentBeforeCreatingTicket() {
        when(jdbcTemplate.queryForObject(contains("FROM agent_consents"), eq(Integer.class), eq(10L)))
                .thenReturn(0);

        assertThatThrownBy(() -> service.uploadLogs(
                AGENT,
                new MockMultipartFile("file", "agent-log.jsonl.gz", "application/gzip", gzip("demo log\n")),
                MockData.map("rangeMinutes", 30),
                "upload-key"
        ))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));

        verify(jdbcTemplate, never()).queryForMap(contains("INSERT INTO as_tickets"), any(), any(), any());
    }

    private static byte[] gzip(String content) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (GZIPOutputStream gzipOutput = new GZIPOutputStream(output)) {
                gzipOutput.write(content.getBytes());
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
