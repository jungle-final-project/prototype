package com.buildgraph.prototype.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SupportChatMigrationContractTest {
    private static final Path MIGRATION = Path.of("src/main/resources/db/migration/V94__support_chat_rooms_split.sql");

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

    private static String normalizedSql() throws Exception {
        return Files.readString(MIGRATION)
                .replaceAll("\\s+", " ")
                .trim();
    }
}
