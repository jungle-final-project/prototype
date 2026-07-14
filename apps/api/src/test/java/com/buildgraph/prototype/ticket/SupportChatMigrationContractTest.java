package com.buildgraph.prototype.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SupportChatMigrationContractTest {
    private static final Path MIGRATION = Path.of("src/main/resources/db/migration/V96__support_chat_rooms_split.sql");
    private static final Path BACKFILL_MIGRATION = Path.of("src/main/resources/db/migration/V97__support_chat_rooms_backfill_repair.sql");
    private static final Path VISIT_RESERVATION_EXACT_TIME_MIGRATION = Path.of("src/main/resources/db/migration/V108__visit_support_reservations_exact_time.sql");
    private static final Path DEMO_SEED_CLEANUP_MIGRATION = Path.of("src/main/resources/db/migration/V118__close_blocking_demo_support_seeds.sql");

    @Test
    void migrationCreatesDedicatedSupportChatTables() throws Exception {
        String sql = normalizedSql();

        assertThat(sql)
                .contains("CREATE TABLE support_chat_rooms")
                .contains("CREATE TABLE support_chat_messages")
                .contains("user_unread_count INTEGER NOT NULL DEFAULT 0")
                .contains("admin_unread_count INTEGER NOT NULL DEFAULT 0")
                .contains("sender_user_id BIGINT REFERENCES users(id)");
    }

    @Test
    void supportChatRoomsKeepActiveUniquePerTicketUser() throws Exception {
        String sql = normalizedSql();

        assertThat(sql)
                .contains("CREATE UNIQUE INDEX ux_support_chat_rooms_active_ticket_user")
                .contains("WHERE status = 'ACTIVE' AND deleted_at IS NULL");
    }

    @Test
    void supportChatMessagesRoleIsRestrictedToHumanAndSystem() throws Exception {
        String sql = normalizedSql();

        assertThat(sql)
                .contains("chk_support_chat_messages_role")
                .contains("role IN ('USER', 'ADMIN', 'SYSTEM')");
    }

    @Test
    void migrationMovesLegacySupportRoomsOutOfAsChatTables() throws Exception {
        String sql = normalizedSql();

        assertThat(sql)
                .contains("INSERT INTO support_chat_rooms")
                .contains("FROM as_chat_sessions s")
                .contains("WHERE s.title = 'AS 상담방'")
                .contains("INSERT INTO support_chat_messages");
    }

    @Test
    void migrationRestoresAsChatTablesToAiChatOnly() throws Exception {
        String sql = normalizedSql();

        assertThat(sql)
                .contains("DELETE FROM as_chat_sessions")
                .contains("chk_as_chat_messages_role")
                .contains("role IN ('USER', 'ASSISTANT')")
                .contains("DROP COLUMN IF EXISTS sender_user_id")
                .contains("DROP COLUMN IF EXISTS last_message_preview");
    }

    @Test
    void backfillRepairCreatesMissingSupportChatRoomsForExistingTickets() throws Exception {
        String sql = normalizedBackfillSql();

        assertThat(sql)
                .contains("INSERT INTO support_chat_rooms")
                .contains("FROM as_tickets t")
                .contains("t.deleted_at IS NULL")
                .contains("NOT EXISTS")
                .contains("support_chat_rooms r");
    }

    @Test
    void backfillRepairReactivatesLatestArchivedRoomWhenNoActiveRoomExists() throws Exception {
        String sql = normalizedBackfillSql();

        assertThat(sql)
                .contains("latest_archived_room")
                .contains("ROW_NUMBER() OVER")
                .contains("status = 'ARCHIVED'")
                .contains("SET status = 'ACTIVE'");
    }

    @Test
    void backfillRepairAddsMissingSystemMessagesAndLastMessageMetadata() throws Exception {
        String sql = normalizedBackfillSql();

        assertThat(sql)
                .contains("INSERT INTO support_chat_messages")
                .contains("SYSTEM")
                .contains(SupportChatService.SYSTEM_OPEN_MESSAGE)
                .contains("last_message_preview")
                .contains("last_message_at")
                .contains("updated_at");
    }

    @Test
    void visitReservationMigrationAddsExactScheduledAt() throws Exception {
        String sql = normalizedVisitReservationSql();

        assertThat(sql)
                .contains("ALTER TABLE visit_support_reservations")
                .contains("ADD COLUMN IF NOT EXISTS scheduled_at TIMESTAMPTZ")
                .contains("idx_visit_support_reservations_scheduled_at")
                .contains("scheduled_at");
    }

    @Test
    void demoSeedCleanupOnlyArchivesFixedSeedTicketsThatBlockNewIntake() throws Exception {
        String sql = normalizedDemoSeedCleanupSql();

        assertThat(sql)
                .contains("UPDATE support_chat_rooms room")
                .contains("SET status = 'ARCHIVED'")
                .contains("UPDATE as_tickets SET status = 'CANCELLED'")
                .contains("00000000-0000-4000-8000-000000006001")
                .contains("00000000-0000-4000-8000-000000006002")
                .doesNotContain("WHERE room.user_id")
                .doesNotContain("WHERE user_id");
    }

    private static String normalizedSql() throws Exception {
        return Files.readString(MIGRATION)
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String normalizedBackfillSql() throws Exception {
        return Files.readString(BACKFILL_MIGRATION)
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String normalizedVisitReservationSql() throws Exception {
        return Files.readString(VISIT_RESERVATION_EXACT_TIME_MIGRATION)
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String normalizedDemoSeedCleanupSql() throws Exception {
        return Files.readString(DEMO_SEED_CLEANUP_MIGRATION)
                .replaceAll("\\s+", " ")
                .trim();
    }
}
