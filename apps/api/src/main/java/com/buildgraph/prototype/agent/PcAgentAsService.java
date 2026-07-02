package com.buildgraph.prototype.agent;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.common.ApiException;
import com.buildgraph.prototype.config.security.AgentPrincipal;
import com.buildgraph.prototype.config.security.AgentTokenHasher;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PcAgentAsService {
    private static final String DEMO_ACTIVATION_TOKEN = "demo-agent-activation-token";
    private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("[A-Za-z0-9._:-]{1,160}");
    private static final long MAX_GZIP_BYTES = 10L * 1024L * 1024L;
    private static final long MAX_UNCOMPRESSED_BYTES = 20L * 1024L * 1024L;
    private static final int RECENT_LOG_RANGE_MINUTES = 30;
    private static final Set<String> CONSENT_TYPES = Set.of(
            "LOCAL_COLLECTION",
            "SERVER_UPLOAD",
            "QUALITY_IMPROVEMENT"
    );
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final JdbcTemplate jdbcTemplate;
    private final AgentTokenHasher tokenHasher;
    private final Clock clock;
    private final Supplier<String> tokenGenerator;

    @Autowired
    public PcAgentAsService(JdbcTemplate jdbcTemplate, AgentTokenHasher tokenHasher) {
        this(jdbcTemplate, tokenHasher, Clock.systemUTC(), PcAgentAsService::newAgentToken);
    }

    PcAgentAsService(
            JdbcTemplate jdbcTemplate,
            AgentTokenHasher tokenHasher,
            Clock clock,
            Supplier<String> tokenGenerator
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.tokenHasher = tokenHasher;
        this.clock = clock;
        this.tokenGenerator = tokenGenerator;
    }

    @Transactional
    public Map<String, Object> register(Map<String, Object> request) {
        String activationToken = requiredString(request, "activationToken");
        if (!DEMO_ACTIVATION_TOKEN.equals(activationToken)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Agent activation token is invalid.");
        }

        String rawAgentToken = tokenGenerator.get();
        if (rawAgentToken == null || rawAgentToken.isBlank()) {
            throw new IllegalStateException("Generated agent token must not be blank.");
        }
        String tokenHash = tokenHasher.sha256Hex(rawAgentToken);
        String deviceFingerprintHash = requiredString(request, "deviceFingerprintHash");
        String hostnameHash = string(request, "hostnameHash", null);
        String registrationKey = requiredString(request, "registrationIdempotencyKey");
        validateIdempotencyKey("registrationIdempotencyKey", registrationKey);
        String osVersion = requiredString(request, "osVersion");
        String agentVersion = requiredString(request, "agentVersion");
        String policyVersion = requiredString(request, "policyVersion");
        String userEmail = string(request, "userEmail", "user@example.com");

        Map<String, Object> row = refreshExistingRegistration(
                userEmail,
                registrationKey,
                tokenHash,
                deviceFingerprintHash,
                hostnameHash,
                osVersion,
                agentVersion,
                policyVersion
        );
        if (row == null) {
            row = insertRegistration(
                    userEmail,
                    deviceFingerprintHash,
                    hostnameHash,
                    tokenHash,
                    registrationKey,
                    osVersion,
                    agentVersion,
                    policyVersion
            );
        }

        return MockData.map(
                "deviceId", DbValueMapper.string(row, "device_id"),
                "status", DbValueMapper.string(row, "status"),
                "agentToken", rawAgentToken,
                "tokenType", "Bearer"
        );
    }

    private Map<String, Object> refreshExistingRegistration(
            String userEmail,
            String registrationKey,
            String tokenHash,
            String deviceFingerprintHash,
            String hostnameHash,
            String osVersion,
            String agentVersion,
            String policyVersion
    ) {
        List<Map<String, Object>> existingRows = jdbcTemplate.queryForList("""
                SELECT id AS device_internal_id,
                       public_id::text AS device_id,
                       status
                FROM agent_devices
                WHERE user_id = (SELECT id FROM users WHERE email = ?)
                  AND registration_idempotency_key = ?
                  AND status IN ('PENDING_REGISTERED', 'ACTIVE', 'UPDATE_REQUIRED')
                ORDER BY id DESC
                LIMIT 1
                """, userEmail, registrationKey);
        if (existingRows.isEmpty()) {
            return null;
        }

        Long deviceInternalId = longValue(existingRows.get(0), "device_internal_id");
        return jdbcTemplate.queryForMap("""
                UPDATE agent_devices
                SET device_fingerprint_hash = ?,
                    hostname_hash = ?,
                    agent_token_hash = ?,
                    status = 'ACTIVE',
                    os_version = ?,
                    agent_version = ?,
                    policy_version = ?,
                    updated_at = now()
                WHERE id = ?
                RETURNING id AS device_internal_id, public_id::text AS device_id, status
                """,
                deviceFingerprintHash,
                hostnameHash,
                tokenHash,
                osVersion,
                agentVersion,
                policyVersion,
                deviceInternalId
        );
    }

    private Map<String, Object> insertRegistration(
            String userEmail,
            String deviceFingerprintHash,
            String hostnameHash,
            String tokenHash,
            String registrationKey,
            String osVersion,
            String agentVersion,
            String policyVersion
    ) {
        try {
            return jdbcTemplate.queryForMap("""
                    INSERT INTO agent_devices (
                      user_id,
                      activation_token_id,
                      device_fingerprint_hash,
                      hostname_hash,
                      agent_token_hash,
                      registration_idempotency_key,
                      status,
                      os_version,
                      agent_version,
                      policy_version,
                      updated_at
                    )
                    VALUES (
                      (SELECT id FROM users WHERE email = ?),
                      NULL,
                      ?,
                      ?,
                      ?,
                      ?,
                      'ACTIVE',
                      ?,
                      ?,
                      ?,
                      now()
                    )
                    RETURNING id AS device_internal_id, public_id::text AS device_id, status
                    """,
                    userEmail,
                    deviceFingerprintHash,
                    hostnameHash,
                    tokenHash,
                    registrationKey,
                    osVersion,
                    agentVersion,
                    policyVersion
            );
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Agent device is already registered.", exception);
        }
    }

    @Transactional
    public Map<String, Object> saveConsent(
            AgentPrincipal principal,
            Map<String, Object> request,
            String idempotencyKey
    ) {
        String consentType = requiredString(request, "consentType");
        if (!CONSENT_TYPES.contains(consentType)) {
            throw badRequest("consentType is invalid.");
        }
        String policyVersion = requiredString(request, "policyVersion");
        boolean accepted = requiredBoolean(request, "accepted");
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                INSERT INTO agent_consents (
                  user_id,
                  device_id,
                  consent_type,
                  policy_version,
                  source,
                  idempotency_key,
                  accepted,
                  accepted_at,
                  revoked_at
                )
                VALUES (
                  ?,
                  ?,
                  ?,
                  ?,
                  'AGENT',
                  ?,
                  ?,
                  CASE WHEN ? THEN now() ELSE NULL END,
                  CASE WHEN ? THEN NULL ELSE now() END
                )
                RETURNING public_id::text AS id, consent_type, policy_version, accepted, accepted_at, revoked_at
                """,
                principal.userInternalId(),
                principal.deviceInternalId(),
                consentType,
                policyVersion,
                idempotencyKey,
                accepted,
                accepted,
                accepted
        );
        return MockData.map(
                "id", DbValueMapper.string(row, "id"),
                "consentType", DbValueMapper.string(row, "consent_type"),
                "policyVersion", DbValueMapper.string(row, "policy_version"),
                "accepted", row.get("accepted"),
                "acceptedAt", DbValueMapper.timestamp(row, "accepted_at"),
                "revokedAt", DbValueMapper.timestamp(row, "revoked_at")
        );
    }

    @Transactional
    public Map<String, Object> heartbeat(
            AgentPrincipal principal,
            Map<String, Object> request,
            String idempotencyKey
    ) {
        String agentVersion = requiredString(request, "agentVersion");
        String serviceStatus = requiredString(request, "serviceStatus");
        String policyVersion = string(request, "policyVersion", null);
        Map<String, Object> deviceRow = updateHeartbeatDevice(principal, agentVersion, policyVersion);

        Map<String, Object> row = jdbcTemplate.queryForMap("""
                INSERT INTO agent_heartbeats (
                  device_id,
                  agent_version,
                  service_status,
                  tray_status,
                  policy_version,
                  idempotency_key,
                  received_at
                )
                VALUES (?, ?, ?, ?, ?, ?, now())
                RETURNING public_id::text AS id, received_at
                """,
                principal.deviceInternalId(),
                agentVersion,
                serviceStatus,
                string(request, "trayStatus", null),
                policyVersion,
                idempotencyKey
        );

        return MockData.map(
                "id", DbValueMapper.string(row, "id"),
                "deviceId", principal.deviceId(),
                "status", DbValueMapper.string(deviceRow, "status"),
                "lastSeenAt", DbValueMapper.timestamp(deviceRow, "last_seen_at"),
                "receivedAt", DbValueMapper.timestamp(row, "received_at"),
                "pendingCommands", java.util.List.of()
        );
    }

    private Map<String, Object> updateHeartbeatDevice(
            AgentPrincipal principal,
            String agentVersion,
            String policyVersion
    ) {
        try {
            return jdbcTemplate.queryForMap("""
                    UPDATE agent_devices
                    SET last_seen_at = now(),
                        agent_version = ?,
                        policy_version = COALESCE(?, policy_version),
                        updated_at = now()
                    WHERE id = ?
                      AND user_id = ?
                      AND status IN ('ACTIVE', 'UPDATE_REQUIRED')
                    RETURNING status, last_seen_at
                    """,
                    agentVersion,
                    policyVersion,
                    principal.deviceInternalId(),
                    principal.userInternalId()
            );
        } catch (EmptyResultDataAccessException exception) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Agent device is not active.", exception);
        }
    }

    @Transactional
    public Map<String, Object> uploadLogs(
            AgentPrincipal principal,
            MultipartFile file,
            Map<String, Object> metadata,
            String idempotencyKey
    ) {
        if (file == null || file.isEmpty()) {
            throw fileValidation("Agent log gzip file is required.");
        }
        String fileName = fileName(file);
        if (!fileName.endsWith(".gz")) {
            throw fileValidation("Agent log upload must be gzip.");
        }
        GzipValidation gzip = validateGzip(file);
        int rangeMinutes = requiredInteger(metadata, "rangeMinutes");
        Instant rangeEndedAt = instant(metadata, "rangeEndedAt", Instant.now(clock));
        Instant rangeStartedAt = instant(metadata, "rangeStartedAt", rangeEndedAt.minus(Duration.ofMinutes(rangeMinutes)));
        validateRecentThirtyMinuteRange(rangeMinutes, rangeStartedAt, rangeEndedAt);
        Integer consentCount = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM agent_consents
                WHERE device_id = ?
                  AND consent_type = 'SERVER_UPLOAD'
                  AND accepted = true
                  AND revoked_at IS NULL
                """, Integer.class, principal.deviceInternalId());
        if (consentCount == null || consentCount == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Server upload consent is required.");
        }

        Map<String, Object> uploadJob = jdbcTemplate.queryForMap("""
                INSERT INTO agent_upload_jobs (
                  device_id,
                  idempotency_key,
                  status,
                  range_started_at,
                  range_ended_at,
                  updated_at
                )
                VALUES (?, ?, 'UPLOADED', ?, ?, now())
                RETURNING id AS upload_job_internal_id, public_id::text AS upload_job_id, status
                """,
                principal.deviceInternalId(),
                idempotencyKey,
                rangeStartedAt,
                rangeEndedAt
        );

        Long uploadJobInternalId = longValue(uploadJob, "upload_job_internal_id");
        String storagePath = "agent-logs/" + principal.deviceId() + "/" + fileName;
        Map<String, Object> logUpload = jdbcTemplate.queryForMap("""
                INSERT INTO agent_log_uploads (
                  user_id,
                  device_id,
                  upload_job_id,
                  range_minutes,
                  status,
                  file_name,
                  file_size,
                  storage_path,
                  summary,
                  consent_accepted_at,
                  delete_after
                )
                VALUES (?, ?, ?, ?, 'UPLOADED', ?, ?, ?, 'Rule demo upload accepted.', now(), now() + interval '30 days')
                RETURNING id AS log_upload_internal_id,
                          public_id::text AS log_upload_id,
                          status,
                          file_name,
                          file_size,
                          range_minutes,
                          delete_after
                """,
                principal.userInternalId(),
                principal.deviceInternalId(),
                uploadJobInternalId,
                rangeMinutes,
                fileName,
                gzip.compressedBytes(),
                storagePath
        );

        Long logUploadInternalId = longValue(logUpload, "log_upload_internal_id");
        jdbcTemplate.queryForMap("""
                INSERT INTO agent_log_bundles (
                  upload_job_id,
                  log_upload_id,
                  schema_version,
                  storage_path,
                  sha256,
                  size_bytes,
                  delete_after
                )
                VALUES (?, ?, ?, ?, ?, ?, COALESCE(?, now() + interval '30 days'))
                RETURNING public_id::text AS log_bundle_id
                """,
                uploadJobInternalId,
                logUploadInternalId,
                integer(metadata, "schemaVersion", 1),
                storagePath,
                gzip.sha256(),
                gzip.compressedBytes(),
                DbValueMapper.timestamp(logUpload, "delete_after")
        );
        String symptom = string(metadata, "symptom", "Agent uploaded recent 30 minute diagnostic log.");
        Map<String, Object> ticket = jdbcTemplate.queryForMap("""
                INSERT INTO as_tickets (
                  user_id,
                  log_upload_id,
                  symptom,
                  status,
                  analysis_status,
                  review_status,
                  support_decision,
                  risk_level,
                  auto_response_allowed,
                  cause_candidates,
                  upgrade_candidates,
                  admin_note,
                  updated_at
                )
                VALUES (
                  ?,
                  ?,
                  ?,
                  'OPEN',
                  'RULE_READY',
                  'REQUIRED',
                  'NEEDS_MORE_INFO',
                  'MEDIUM',
                  false,
                  '[{"label":"Recent agent log uploaded","confidence":"MEDIUM","reason":"Demo rule diagnosis placeholder"}]'::jsonb,
                  '[]'::jsonb,
                  'Rule-based demo diagnosis is ready for admin review.',
                  now()
                )
                RETURNING public_id::text AS ticket_id,
                          status,
                          analysis_status,
                          review_status,
                          support_decision
                """,
                principal.userInternalId(),
                logUploadInternalId,
                symptom
        );

        return MockData.map(
                "uploadJobId", DbValueMapper.string(uploadJob, "upload_job_id"),
                "logUploadId", DbValueMapper.string(logUpload, "log_upload_id"),
                "ticketId", DbValueMapper.string(ticket, "ticket_id"),
                "status", DbValueMapper.string(ticket, "status"),
                "analysisStatus", DbValueMapper.string(ticket, "analysis_status"),
                "reviewStatus", DbValueMapper.string(ticket, "review_status"),
                "supportDecision", DbValueMapper.string(ticket, "support_decision"),
                "rangeMinutes", rangeMinutes
        );
    }

    private static GzipValidation validateGzip(MultipartFile file) {
        byte[] compressed;
        try {
            compressed = file.getBytes();
        } catch (IOException exception) {
            throw fileValidation("Agent log gzip file cannot be read.");
        }
        if (compressed.length == 0) {
            throw fileValidation("Agent log gzip file is empty.");
        }
        if (compressed.length > MAX_GZIP_BYTES) {
            throw fileValidation("Agent log gzip file is too large.");
        }
        long uncompressedBytes = 0L;
        byte[] buffer = new byte[8192];
        try (GZIPInputStream gzipInputStream = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            int read;
            while ((read = gzipInputStream.read(buffer)) != -1) {
                uncompressedBytes += read;
                if (uncompressedBytes > MAX_UNCOMPRESSED_BYTES) {
                    throw fileValidation("Agent log gzip content is too large.");
                }
            }
        } catch (IOException exception) {
            throw fileValidation("Agent log upload must contain valid gzip content.");
        }
        if (uncompressedBytes == 0L) {
            throw fileValidation("Agent log gzip content is empty.");
        }
        return new GzipValidation(compressed.length, uncompressedBytes, sha256Hex(compressed));
    }

    private static void validateRecentThirtyMinuteRange(int rangeMinutes, Instant rangeStartedAt, Instant rangeEndedAt) {
        if (rangeMinutes != RECENT_LOG_RANGE_MINUTES) {
            throw badRequest("Agent log upload rangeMinutes must be 30.");
        }
        if (!rangeEndedAt.isAfter(rangeStartedAt)) {
            throw badRequest("Agent log rangeEndedAt must be after rangeStartedAt.");
        }
        Duration duration = Duration.between(rangeStartedAt, rangeEndedAt);
        if (duration.isNegative() || duration.compareTo(Duration.ofMinutes(RECENT_LOG_RANGE_MINUTES)) > 0) {
            throw badRequest("Agent log upload range must be within recent 30 minutes.");
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available.", exception);
        }
    }

    private static String newAgentToken() {
        byte[] token = new byte[32];
        SECURE_RANDOM.nextBytes(token);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    private static String fileName(MultipartFile file) {
        String original = file.getOriginalFilename();
        if (original == null || original.isBlank()) {
            return "agent-log.jsonl.gz";
        }
        return original.replace("\\", "/").substring(original.replace("\\", "/").lastIndexOf('/') + 1);
    }

    private static String string(Map<String, Object> request, String key, String fallback) {
        if (request == null || request.get(key) == null) {
            return fallback;
        }
        String value = request.get(key).toString();
        return value.isBlank() ? fallback : value;
    }

    private static String requiredString(Map<String, Object> request, String key) {
        String value = string(request, key, null);
        if (value == null) {
            throw badRequest(key + " is required.");
        }
        return value;
    }

    private static boolean requiredBoolean(Map<String, Object> request, String key) {
        if (request == null || request.get(key) == null) {
            throw badRequest(key + " is required.");
        }
        Object value = request.get(key);
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        String text = value.toString();
        if ("true".equalsIgnoreCase(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text)) {
            return false;
        }
        throw badRequest(key + " must be boolean.");
    }

    private static void validateIdempotencyKey(String fieldName, String value) {
        if (!IDEMPOTENCY_KEY_PATTERN.matcher(value).matches()) {
            throw badRequest(fieldName + " is invalid.");
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ApiException fileValidation(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "FILE_VALIDATION_ERROR", message);
    }

    private static int integer(Map<String, Object> request, String key, int fallback) {
        if (request == null || request.get(key) == null) {
            return fallback;
        }
        Object value = request.get(key);
        return value instanceof Number number ? number.intValue() : Integer.parseInt(value.toString());
    }

    private static int requiredInteger(Map<String, Object> request, String key) {
        if (request == null || request.get(key) == null) {
            throw badRequest(key + " is required.");
        }
        return integer(request, key, 0);
    }

    private static Instant instant(Map<String, Object> request, String key, Instant fallback) {
        if (request == null || request.get(key) == null) {
            return fallback;
        }
        return Instant.parse(request.get(key).toString());
    }

    private static Long longValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? null : Long.valueOf(value.toString());
    }

    private record GzipValidation(long compressedBytes, long uncompressedBytes, String sha256) {
    }
}
