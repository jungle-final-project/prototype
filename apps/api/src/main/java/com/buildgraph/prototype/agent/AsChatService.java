package com.buildgraph.prototype.agent;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.part.ToolCheckService;
import com.buildgraph.prototype.user.CurrentUserService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AsChatService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final String SYSTEM_PROMPT = """
            당신은 BuildGraph AS AI 상담 챗봇입니다.
            반드시 JSON 객체 하나만 반환하십시오. 마크다운, 코드블록, 설명 문장을 JSON 밖에 쓰지 마십시오.
            제공된 AS 티켓 증상, 최근 대화, RAG 근거, Tool 결과만 근거로 답하십시오.
            확인되지 않은 부품명, 가격, FPS, 수리 비용, 성능 수치를 지어내지 마십시오.
            사용자가 직접 시도할 수 있는 안전한 확인 절차와 원격지원/기사 연결 필요 여부를 구분하십시오.
            필수 JSON 필드:
            assistantMessage: 사용자에게 보여줄 한국어 답변 문자열
            causeCandidates: [{label, confidence, reason}]
            nextActions: [{label, priority, instruction}]
            escalation: {required, reason}
            ticketDraft: {symptomSummary, recommendedLogRequest}
            """;

    private final JdbcTemplate jdbcTemplate;
    private final AgentTraceService agentTraceService;
    private final AgentRagRetrievalService agentRagRetrievalService;
    private final OpenAiResponsesClient openAiResponsesClient;
    private final ToolCheckService toolCheckService;

    public AsChatService(
            JdbcTemplate jdbcTemplate,
            AgentTraceService agentTraceService,
            AgentRagRetrievalService agentRagRetrievalService,
            OpenAiResponsesClient openAiResponsesClient,
            ToolCheckService toolCheckService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.agentTraceService = agentTraceService;
        this.agentRagRetrievalService = agentRagRetrievalService;
        this.openAiResponsesClient = openAiResponsesClient;
        this.toolCheckService = toolCheckService;
    }

    public Map<String, Object> history(String asTicketId, CurrentUserService.CurrentUser user) {
        String ticketId = requireText(asTicketId, "AS 티켓 ID가 필요합니다.");
        TicketRow ticket = ticket(ticketId, user);
        ChatSessionRow session = findActiveSession(ticket.internalId(), user.internalId());
        List<Map<String, Object>> messages = session == null ? List.of() : messages(session.internalId());
        return MockData.map(
                "sessionId", session == null ? null : session.publicId(),
                "asTicketId", ticket.publicId(),
                "ticket", ticketMap(ticket),
                "model", openAiResponsesClient.model(),
                "messages", messages,
                "evidence", List.of(),
                "toolResults", List.of()
        );
    }

    public Map<String, Object> send(String asTicketId, String message, CurrentUserService.CurrentUser user) {
        if (!openAiResponsesClient.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "OPENAI_API_KEY가 필요합니다.");
        }

        String ticketId = requireText(asTicketId, "AS 티켓 ID가 필요합니다.");
        String userMessage = requireText(message, "챗봇에 보낼 메시지가 필요합니다.");
        TicketRow ticket = ticket(ticketId, user);
        ChatSessionRow chatSession = findOrCreateActiveSession(ticket, user);
        saveMessage(chatSession.internalId(), "USER", userMessage, Map.of(), null);

        AgentSessionRoot root = new AgentSessionRoot(AgentSessionRootType.AS_TICKET, ticket.publicId());
        AgentRunProfile profile = AgentRunProfiles.forRoot(root);
        String agentSessionId = agentTraceService.createQueuedSession(root, "USER", profile.purpose(), user.internalId());
        Long agentInternalId = agentInternalId(agentSessionId);
        try {
            agentTraceService.advanceStatus(agentSessionId, AgentStatus.RUNNING, "SYSTEM", "AS AI chat requested");

            String retrievalQuery = retrievalQuery(ticket, recentConversation(chatSession.internalId()), userMessage);
            List<AgentRagEvidenceDraft> evidenceDrafts = agentRagRetrievalService.retrieveEvidenceSet(root, profile, retrievalQuery, 4);
            List<String> evidenceIds = evidenceDrafts.stream()
                    .map(draft -> agentTraceService.recordRagEvidence(agentSessionId, draft))
                    .toList();
            agentTraceService.advanceStatus(agentSessionId, AgentStatus.RAG_SEARCHED, "SYSTEM", "AS chat RAG evidence retrieved");

            List<AgentToolInvocationDraft> toolDrafts = toolInvocations(root, profile);
            List<String> toolInvocationIds = toolDrafts.stream()
                    .map(draft -> agentTraceService.recordToolInvocation(agentSessionId, draft))
                    .toList();
            agentTraceService.advanceStatus(agentSessionId, AgentStatus.TOOLS_CALLED, "SYSTEM", "AS chat Tool checks completed");

            List<Map<String, Object>> evidence = evidenceItems(evidenceIds, evidenceDrafts);
            List<Map<String, Object>> toolResults = toolItems(toolInvocationIds, toolDrafts);
            Map<String, Object> llmJson = strictJson(openAiResponsesClient.createSummary(
                    SYSTEM_PROMPT,
                    llmPrompt(ticket, recentConversation(chatSession.internalId()), userMessage, evidence, toolResults)
            ));
            String assistantMessage = requireAssistantMessage(llmJson);
            agentTraceService.updateSummary(agentSessionId, assistantMessage);
            agentTraceService.advanceStatus(agentSessionId, AgentStatus.SUMMARY_READY, "SYSTEM", "AS chat LLM JSON generated by " + openAiResponsesClient.model());
            agentTraceService.advanceStatus(agentSessionId, AgentStatus.SUCCEEDED, "SYSTEM", "AS chat completed");

            saveMessage(chatSession.internalId(), "ASSISTANT", assistantMessage, llmJson, agentInternalId);
            return response(ticket, chatSession, agentSessionId, assistantMessage, llmJson, evidence, toolResults);
        } catch (ResponseStatusException error) {
            failAgentSession(agentSessionId, error);
            throw error;
        } catch (RuntimeException error) {
            failAgentSession(agentSessionId, error);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "LLM 응답 JSON을 처리할 수 없습니다.", error);
        }
    }

    private TicketRow ticket(String asTicketId, CurrentUserService.CurrentUser user) {
        return jdbcTemplate.queryForList("""
                        SELECT t.id AS internal_id,
                               t.public_id::text AS id,
                               t.symptom,
                               t.status,
                               coalesce(t.cause_candidates::text, '[]') AS cause_candidates,
                               coalesce(t.upgrade_candidates::text, '[]') AS upgrade_candidates,
                               coalesce(l.summary, '') AS log_summary,
                               t.created_at
                        FROM as_tickets t
                        LEFT JOIN agent_log_uploads l ON l.id = t.log_upload_id
                        WHERE t.public_id = ?::uuid
                          AND t.user_id = ?
                          AND t.deleted_at IS NULL
                        """, asTicketId, user.internalId())
                .stream()
                .findFirst()
                .map(this::ticketRow)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "AS 티켓을 찾을 수 없습니다."));
    }

    private ChatSessionRow findActiveSession(Long ticketInternalId, Long userInternalId) {
        return jdbcTemplate.queryForList("""
                        SELECT id AS internal_id,
                               public_id::text AS id,
                               title,
                               status
                        FROM as_chat_sessions
                        WHERE user_id = ?
                          AND as_ticket_id = ?
                          AND status = 'ACTIVE'
                          AND deleted_at IS NULL
                        ORDER BY id DESC
                        LIMIT 1
                        """, userInternalId, ticketInternalId)
                .stream()
                .findFirst()
                .map(this::chatSessionRow)
                .orElse(null);
    }

    private ChatSessionRow findOrCreateActiveSession(TicketRow ticket, CurrentUserService.CurrentUser user) {
        ChatSessionRow existing = findActiveSession(ticket.internalId(), user.internalId());
        if (existing != null) {
            return existing;
        }
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                INSERT INTO as_chat_sessions (user_id, as_ticket_id, title, updated_at)
                VALUES (?, ?, ?, now())
                RETURNING id AS internal_id, public_id::text AS id, title, status
                """, user.internalId(), ticket.internalId(), titleFromSymptom(ticket.symptom()));
        return chatSessionRow(row);
    }

    private Long agentInternalId(String agentSessionId) {
        return jdbcTemplate.queryForList("""
                        SELECT id
                        FROM agent_sessions
                        WHERE public_id = ?::uuid
                        """, agentSessionId)
                .stream()
                .findFirst()
                .map(row -> longValue(row, "id"))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent session을 찾을 수 없습니다."));
    }

    private void saveMessage(Long chatSessionId, String role, String content, Map<String, Object> payload, Long agentInternalId) {
        jdbcTemplate.update("""
                INSERT INTO as_chat_messages (
                  chat_session_id,
                  role,
                  content,
                  structured_payload,
                  agent_session_id
                )
                VALUES (?, ?, ?, ?::jsonb, ?)
                """, chatSessionId, role, content, AgentTraceService.json(payload == null ? Map.of() : payload), agentInternalId);
        jdbcTemplate.update("""
                UPDATE as_chat_sessions
                SET updated_at = now()
                WHERE id = ?
                """, chatSessionId);
    }

    private List<Map<String, Object>> messages(Long chatSessionId) {
        return jdbcTemplate.queryForList("""
                        SELECT m.public_id::text AS id,
                               m.role,
                               m.content,
                               m.structured_payload,
                               s.public_id::text AS agent_session_id,
                               m.created_at
                        FROM as_chat_messages m
                        LEFT JOIN agent_sessions s ON s.id = m.agent_session_id
                        WHERE m.chat_session_id = ?
                        ORDER BY m.created_at, m.id
                        """, chatSessionId)
                .stream()
                .map(this::messageMap)
                .toList();
    }

    private List<Map<String, Object>> recentConversation(Long chatSessionId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT role, content, created_at
                FROM as_chat_messages
                WHERE chat_session_id = ?
                ORDER BY created_at DESC, id DESC
                LIMIT 8
                """, chatSessionId);
        return rows.reversed().stream()
                .map(row -> MockData.map(
                        "role", DbValueMapper.string(row, "role"),
                        "content", DbValueMapper.string(row, "content"),
                        "createdAt", DbValueMapper.timestamp(row, "created_at")
                ))
                .toList();
    }

    private List<AgentToolInvocationDraft> toolInvocations(AgentSessionRoot root, AgentRunProfile profile) {
        try {
            return AgentRunTraceDrafts.toolInvocationsFromResults(
                    root,
                    profile,
                    toolCheckService.checkAgentTools(root.type().name(), root.publicId(), profile.toolNames())
            );
        } catch (RuntimeException ignored) {
            return AgentRunTraceDrafts.toolInvocations(root, profile);
        }
    }

    private Map<String, Object> response(
            TicketRow ticket,
            ChatSessionRow chatSession,
            String agentSessionId,
            String assistantMessage,
            Map<String, Object> llmJson,
            List<Map<String, Object>> evidence,
            List<Map<String, Object>> toolResults
    ) {
        return MockData.map(
                "sessionId", chatSession.publicId(),
                "asTicketId", ticket.publicId(),
                "ticket", ticketMap(ticket),
                "model", openAiResponsesClient.model(),
                "agentSessionId", agentSessionId,
                "messages", messages(chatSession.internalId()),
                "assistantMessage", assistantMessage,
                "causeCandidates", listValue(llmJson.get("causeCandidates")),
                "nextActions", listValue(llmJson.get("nextActions")),
                "escalation", objectValue(llmJson.get("escalation")),
                "ticketDraft", objectValue(llmJson.get("ticketDraft")),
                "evidence", evidence,
                "toolResults", toolResults
        );
    }

    private static String llmPrompt(
            TicketRow ticket,
            List<Map<String, Object>> recentMessages,
            String userMessage,
            List<Map<String, Object>> evidence,
            List<Map<String, Object>> toolResults
    ) {
        return AgentTraceService.json(MockData.map(
                "task", "AS_ANALYZE_CHAT",
                "ticket", MockData.map(
                        "id", ticket.publicId(),
                        "status", ticket.status(),
                        "symptom", ticket.symptom(),
                        "logSummary", ticket.logSummary(),
                        "existingCauseCandidates", ticket.causeCandidates(),
                        "existingUpgradeCandidates", ticket.upgradeCandidates()
                ),
                "recentMessages", recentMessages,
                "latestUserMessage", userMessage,
                "ragEvidence", evidence,
                "toolResults", toolResults,
                "outputContract", MockData.map(
                        "assistantMessage", "string",
                        "causeCandidates", List.of(MockData.map("label", "string", "confidence", "LOW|MEDIUM|HIGH", "reason", "string")),
                        "nextActions", List.of(MockData.map("label", "string", "priority", "LOW|MEDIUM|HIGH", "instruction", "string")),
                        "escalation", MockData.map("required", "boolean", "reason", "string"),
                        "ticketDraft", MockData.map("symptomSummary", "string", "recommendedLogRequest", "string")
                )
        ));
    }

    private static String retrievalQuery(TicketRow ticket, List<Map<String, Object>> recentMessages, String userMessage) {
        return String.join(" ",
                safe(ticket.symptom()),
                safe(ticket.logSummary()),
                safe(AgentTraceService.json(recentMessages)),
                safe(userMessage)
        );
    }

    private static List<Map<String, Object>> evidenceItems(List<String> ids, List<AgentRagEvidenceDraft> drafts) {
        return java.util.stream.IntStream.range(0, drafts.size())
                .mapToObj(index -> {
                    AgentRagEvidenceDraft draft = drafts.get(index);
                    return MockData.map(
                            "id", ids.get(index),
                            "sourceId", draft.sourceId(),
                            "summary", draft.summary(),
                            "chunkText", draft.chunkText(),
                            "score", draft.score(),
                            "metadata", draft.metadata()
                    );
                })
                .toList();
    }

    private static List<Map<String, Object>> toolItems(List<String> ids, List<AgentToolInvocationDraft> drafts) {
        return java.util.stream.IntStream.range(0, drafts.size())
                .mapToObj(index -> {
                    AgentToolInvocationDraft draft = drafts.get(index);
                    return MockData.map(
                            "id", ids.get(index),
                            "toolName", draft.toolName(),
                            "status", draft.status().name(),
                            "confidence", draft.confidence().name(),
                            "summary", draft.summary(),
                            "resultPayload", draft.resultPayload()
                    );
                })
                .toList();
    }

    private Map<String, Object> strictJson(String raw) {
        try {
            Map<String, Object> parsed = OBJECT_MAPPER.readValue(raw, MAP_TYPE);
            requireJsonField(parsed, "assistantMessage");
            requireJsonField(parsed, "causeCandidates");
            requireJsonField(parsed, "nextActions");
            requireJsonField(parsed, "escalation");
            requireJsonField(parsed, "ticketDraft");
            return parsed;
        } catch (ResponseStatusException error) {
            throw error;
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "LLM이 JSON 계약을 지키지 않았습니다.", error);
        }
    }

    private static void requireJsonField(Map<String, Object> parsed, String key) {
        if (!parsed.containsKey(key) || parsed.get(key) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "LLM 응답에 필수 필드가 없습니다: " + key);
        }
    }

    private static String requireAssistantMessage(Map<String, Object> parsed) {
        Object value = parsed.get("assistantMessage");
        if (!(value instanceof String message) || message.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "LLM assistantMessage가 비어 있습니다.");
        }
        return message.trim();
    }

    private void failAgentSession(String agentSessionId, RuntimeException error) {
        try {
            agentTraceService.updateSummary(agentSessionId, "AS chat failed: " + safeReason(error));
            agentTraceService.advanceStatus(agentSessionId, AgentStatus.FAILED, "SYSTEM", "AS chat failed");
        } catch (RuntimeException ignored) {
            // Failure recording must not hide the original API error.
        }
    }

    private TicketRow ticketRow(Map<String, Object> row) {
        return new TicketRow(
                longValue(row, "internal_id"),
                DbValueMapper.string(row, "id"),
                DbValueMapper.string(row, "symptom"),
                DbValueMapper.string(row, "status"),
                DbValueMapper.json(row, "cause_candidates", List.of()),
                DbValueMapper.json(row, "upgrade_candidates", List.of()),
                DbValueMapper.string(row, "log_summary"),
                DbValueMapper.timestamp(row, "created_at")
        );
    }

    private ChatSessionRow chatSessionRow(Map<String, Object> row) {
        return new ChatSessionRow(
                longValue(row, "internal_id"),
                DbValueMapper.string(row, "id"),
                DbValueMapper.string(row, "title"),
                DbValueMapper.string(row, "status")
        );
    }

    private Map<String, Object> ticketMap(TicketRow ticket) {
        return MockData.map(
                "id", ticket.publicId(),
                "status", ticket.status(),
                "symptom", ticket.symptom(),
                "logSummary", ticket.logSummary(),
                "causeCandidates", ticket.causeCandidates(),
                "upgradeCandidates", ticket.upgradeCandidates(),
                "createdAt", ticket.createdAt()
        );
    }

    private Map<String, Object> messageMap(Map<String, Object> row) {
        return MockData.map(
                "id", DbValueMapper.string(row, "id"),
                "role", DbValueMapper.string(row, "role"),
                "content", DbValueMapper.string(row, "content"),
                "structuredPayload", DbValueMapper.json(row, "structured_payload", Map.of()),
                "agentSessionId", DbValueMapper.string(row, "agent_session_id"),
                "createdAt", DbValueMapper.timestamp(row, "created_at")
        );
    }

    private static String titleFromSymptom(String symptom) {
        String text = safe(symptom).replaceAll("\\s+", " ").trim();
        if (text.isBlank()) {
            return "AS AI 챗봇 상담";
        }
        return text.length() > 80 ? text.substring(0, 80) : text;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return value.trim();
    }

    private static Long longValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? null : Long.valueOf(value.toString());
    }

    private static List<?> listValue(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static Map<String, Object> objectValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, mapValue) -> result.put(String.valueOf(key), mapValue));
            return result;
        }
        return Map.of();
    }

    private static String safeReason(RuntimeException error) {
        if (error instanceof ResponseStatusException responseStatusException && responseStatusException.getReason() != null) {
            return responseStatusException.getReason();
        }
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private record TicketRow(
            Long internalId,
            String publicId,
            String symptom,
            String status,
            Object causeCandidates,
            Object upgradeCandidates,
            String logSummary,
            Object createdAt
    ) {
    }

    private record ChatSessionRow(Long internalId, String publicId, String title, String status) {
    }
}
