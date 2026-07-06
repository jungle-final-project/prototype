package com.buildgraph.prototype.ticket;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.user.CurrentUserService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class VisitSupportReservationService {
    private static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DISPLAY_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Set<String> TERMINAL_TICKET_STATUSES = Set.of("CLOSED", "CANCELLED");
    private static final Set<String> ACTIVE_RESERVATION_STATUSES = Set.of(
            "REQUESTED",
            "RESCHEDULE_REQUESTED",
            "SCHEDULED",
            "VISIT_IN_PROGRESS"
    );

    private final JdbcTemplate jdbcTemplate;
    private final SupportChatService supportChatService;
    private final Clock clock;

    @Autowired
    public VisitSupportReservationService(JdbcTemplate jdbcTemplate, SupportChatService supportChatService) {
        this(jdbcTemplate, supportChatService, Clock.system(SEOUL_ZONE));
    }

    VisitSupportReservationService(JdbcTemplate jdbcTemplate, SupportChatService supportChatService, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.supportChatService = supportChatService;
        this.clock = clock;
    }

    @Transactional
    public Map<String, Object> requestUserReservation(
            String roomId,
            Map<String, Object> request,
            CurrentUserService.CurrentUser user
    ) {
        OffsetDateTime scheduledAt = requireScheduledAt(request);
        String addressSnapshot = stringOrNull(request == null ? null : request.get("addressSnapshot"));
        RoomRef room = roomForUser(roomId, user);
        requireTicketOpen(room.ticketStatus());
        lockTicketForUpdate(room.ticketInternalId());
        ReservationRow existing = activeReservation(room.ticketInternalId());
        String status = existing == null ? "REQUESTED" : "RESCHEDULE_REQUESTED";
        persistReservation(existing, room.ticketInternalId(), room.userInternalId(), scheduledAt, status, addressSnapshot, null);
        String systemMessage = (existing == null ? "방문 지원 예약을 요청했습니다: " : "방문 지원 예약 변경을 요청했습니다: ")
                + displayTime(scheduledAt);
        insertSystemMessage(room.roomInternalId(), systemMessage);
        updateAfterSystemMessage(room.roomInternalId(), systemMessage, "USER");
        return supportChatService.detailSnapshot(roomId, user);
    }

    @Transactional
    public Map<String, Object> scheduleAdminReservation(
            String roomId,
            Map<String, Object> request,
            CurrentUserService.CurrentUser admin
    ) {
        OffsetDateTime scheduledAt = requireScheduledAt(request);
        String technicianNote = stringOrNull(request == null ? null : request.get("technicianNote"));
        RoomRef room = roomForAdmin(roomId);
        requireTicketOpen(room.ticketStatus());
        lockTicketForUpdate(room.ticketInternalId());
        ReservationRow existing = activeReservation(room.ticketInternalId());
        persistReservation(existing, room.ticketInternalId(), room.userInternalId(), scheduledAt, "SCHEDULED", null, technicianNote);
        String systemMessage = "방문 지원 예약이 확정되었습니다: " + displayTime(scheduledAt);
        insertSystemMessage(room.roomInternalId(), systemMessage);
        updateAfterSystemMessage(room.roomInternalId(), systemMessage, "ADMIN");
        return supportChatService.adminDetailSnapshot(roomId, admin);
    }

    @Transactional
    public Map<String, Object> cancelAdminReservation(String roomId, CurrentUserService.CurrentUser admin) {
        RoomRef room = roomForAdmin(roomId);
        requireTicketOpen(room.ticketStatus());
        lockTicketForUpdate(room.ticketInternalId());
        ReservationRow existing = activeReservation(room.ticketInternalId());
        if (existing == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "취소할 방문 지원 예약이 없습니다.");
        }
        jdbcTemplate.queryForList("""
                UPDATE visit_support_reservations
                SET status = ?,
                    updated_at = now()
                WHERE id = ?
                RETURNING id AS internal_id,
                          public_id::text AS id,
                          status,
                          scheduled_at,
                          address_snapshot,
                          technician_note,
                          created_at,
                          updated_at
                """, "CANCELLED", existing.internalId());
        String systemMessage = "방문 지원 예약이 취소되었습니다.";
        insertSystemMessage(room.roomInternalId(), systemMessage);
        updateAfterSystemMessage(room.roomInternalId(), systemMessage, "ADMIN");
        return supportChatService.adminDetailSnapshot(roomId, admin);
    }

    private ReservationRow persistReservation(
            ReservationRow existing,
            Long ticketInternalId,
            Long userInternalId,
            OffsetDateTime scheduledAt,
            String status,
            String addressSnapshot,
            String technicianNote
    ) {
        LocalDate preferredDate = scheduledAt.atZoneSameInstant(SEOUL_ZONE).toLocalDate();
        String timeSlot = timeSlot(scheduledAt);
        List<Map<String, Object>> rows;
        if (existing == null) {
            rows = jdbcTemplate.queryForList("""
                    INSERT INTO visit_support_reservations (
                      as_ticket_id,
                      user_id,
                      preferred_date,
                      time_slot,
                      status,
                      scheduled_at,
                      address_snapshot,
                      technician_note,
                      updated_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, now())
                    RETURNING id AS internal_id,
                              public_id::text AS id,
                              status,
                              scheduled_at,
                              address_snapshot,
                              technician_note,
                              created_at,
                              updated_at
                    """, ticketInternalId, userInternalId, preferredDate, timeSlot, status, scheduledAt, addressSnapshot, technicianNote);
        } else {
            rows = jdbcTemplate.queryForList("""
                    UPDATE visit_support_reservations
                    SET status = ?,
                        preferred_date = ?,
                        time_slot = ?,
                        scheduled_at = ?,
                        address_snapshot = COALESCE(?, address_snapshot),
                        technician_note = COALESCE(?, technician_note),
                        updated_at = now()
                    WHERE id = ?
                    RETURNING id AS internal_id,
                              public_id::text AS id,
                              status,
                              scheduled_at,
                              address_snapshot,
                              technician_note,
                              created_at,
                              updated_at
                    """, status, preferredDate, timeSlot, scheduledAt, addressSnapshot, technicianNote, existing.internalId());
        }
        return rows.stream()
                .findFirst()
                .map(this::reservationRow)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "방문 지원 예약을 저장하지 못했습니다."));
    }

    private RoomRef roomForUser(String roomId, CurrentUserService.CurrentUser user) {
        requireUuid(roomId);
        return jdbcTemplate.queryForList(roomSelect() + """
                        WHERE r.public_id = ?::uuid
                          AND r.user_id = ?
                          AND r.deleted_at IS NULL
                          AND t.deleted_at IS NULL
                        """, roomId, user.internalId())
                .stream()
                .findFirst()
                .map(this::roomRef)
                .orElseThrow(() -> notFound("상담방을 찾을 수 없습니다."));
    }

    private RoomRef roomForAdmin(String roomId) {
        requireUuid(roomId);
        return jdbcTemplate.queryForList(roomSelect() + """
                        WHERE r.public_id = ?::uuid
                          AND r.deleted_at IS NULL
                          AND t.deleted_at IS NULL
                        """, roomId)
                .stream()
                .findFirst()
                .map(this::roomRef)
                .orElseThrow(() -> notFound("상담방을 찾을 수 없습니다."));
    }

    private String roomSelect() {
        return """
                SELECT r.id AS room_internal_id,
                       r.public_id::text AS room_id,
                       t.id AS ticket_internal_id,
                       t.public_id::text AS ticket_id,
                       t.status AS ticket_status,
                       r.user_id AS user_internal_id
                FROM support_chat_rooms r
                JOIN as_tickets t ON t.id = r.as_ticket_id
                """;
    }

    private void lockTicketForUpdate(Long ticketInternalId) {
        String status = jdbcTemplate.queryForList("""
                        SELECT status
                        FROM as_tickets
                        WHERE id = ?
                        FOR UPDATE
                        """, ticketInternalId)
                .stream()
                .findFirst()
                .map(row -> DbValueMapper.string(row, "status"))
                .orElseThrow(() -> notFound("AS 티켓을 찾을 수 없습니다."));
        requireTicketOpen(status);
    }

    private ReservationRow activeReservation(Long ticketInternalId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                        SELECT *
                        FROM (
                          SELECT id AS internal_id,
                                 public_id::text AS id,
                                 status,
                                 scheduled_at,
                                 address_snapshot,
                                 technician_note,
                                 created_at,
                                 updated_at
                          FROM visit_support_reservations
                          WHERE as_ticket_id = ?
                            AND status IN ('REQUESTED', 'RESCHEDULE_REQUESTED', 'SCHEDULED', 'VISIT_IN_PROGRESS')
                          ORDER BY COALESCE(updated_at, created_at) DESC, id DESC
                          LIMIT 1
                        ) active_visit_reservation
                        """, ticketInternalId);
        return rows.stream()
                .findFirst()
                .map(this::reservationRow)
                .orElse(null);
    }

    private ReservationRow reservationRow(Map<String, Object> row) {
        return new ReservationRow(
                longValue(row, "internal_id"),
                DbValueMapper.string(row, "id"),
                DbValueMapper.string(row, "status")
        );
    }

    private void insertSystemMessage(Long roomInternalId, String content) {
        jdbcTemplate.update("""
                INSERT INTO support_chat_messages (
                  room_id,
                  role,
                  content,
                  sender_user_id
                )
                VALUES (?, ?, ?, ?)
                """, roomInternalId, "SYSTEM", content, null);
    }

    private void updateAfterSystemMessage(Long roomInternalId, String content, String actorRole) {
        int userUnreadDelta = "ADMIN".equals(actorRole) ? 1 : 0;
        int adminUnreadDelta = "USER".equals(actorRole) ? 1 : 0;
        jdbcTemplate.update("""
                UPDATE support_chat_rooms
                SET last_message_preview = ?,
                    last_message_at = now(),
                    user_unread_count = CASE WHEN ? = 1 THEN user_unread_count + 1 ELSE 0 END,
                    admin_unread_count = CASE WHEN ? = 1 THEN admin_unread_count + 1 ELSE 0 END,
                    updated_at = now()
                WHERE id = ?
                """, preview(content), userUnreadDelta, adminUnreadDelta, roomInternalId);
    }

    private OffsetDateTime requireScheduledAt(Map<String, Object> request) {
        Object raw = request == null ? null : request.get("scheduledAt");
        if (!(raw instanceof String text) || text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "방문 예약 시각을 입력해 주세요.");
        }
        try {
            OffsetDateTime scheduledAt = OffsetDateTime.parse(text.trim());
            if (!scheduledAt.toInstant().isAfter(clock.instant())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "방문 예약 시각은 미래여야 합니다.");
            }
            return scheduledAt;
        } catch (ResponseStatusException error) {
            throw error;
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "방문 예약 시각 형식이 올바르지 않습니다.");
        }
    }

    private static void requireTicketOpen(String ticketStatus) {
        if (TERMINAL_TICKET_STATUSES.contains(ticketStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "종료된 AS 티켓은 방문 지원 예약을 변경할 수 없습니다.");
        }
    }

    private static String timeSlot(OffsetDateTime scheduledAt) {
        int hour = scheduledAt.atZoneSameInstant(SEOUL_ZONE).getHour();
        if (hour < 12) {
            return "MORNING";
        }
        if (hour < 18) {
            return "AFTERNOON";
        }
        return "EVENING";
    }

    private static String displayTime(OffsetDateTime scheduledAt) {
        return scheduledAt.atZoneSameInstant(SEOUL_ZONE).format(DISPLAY_FORMATTER);
    }

    private static String preview(String content) {
        String normalized = content.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 240);
    }

    private static String stringOrNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isBlank() || "null".equalsIgnoreCase(text) ? null : text;
    }

    private static void requireUuid(String value) {
        try {
            UUID.fromString(value);
        } catch (Exception error) {
            throw notFound("상담방을 찾을 수 없습니다.");
        }
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private static Long longValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? null : Long.valueOf(value.toString());
    }

    private RoomRef roomRef(Map<String, Object> row) {
        return new RoomRef(
                longValue(row, "room_internal_id"),
                longValue(row, "ticket_internal_id"),
                DbValueMapper.string(row, "ticket_status"),
                longValue(row, "user_internal_id")
        );
    }

    private record RoomRef(
            Long roomInternalId,
            Long ticketInternalId,
            String ticketStatus,
            Long userInternalId
    ) {
    }

    private record ReservationRow(Long internalId, String publicId, String status) {
    }
}
