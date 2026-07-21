package com.buildgraph.prototype.quote;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class QuoteDraftHistoryMigrationContractTest {
    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V131__quote_draft_history.sql"
    );
    private static final Path EVALUATION_MIGRATION = Path.of(
            "src/main/resources/db/migration/V132__quote_draft_history_evaluation.sql"
    );
    private static final Path EVALUATION_RETRY_MIGRATION = Path.of(
            "src/main/resources/db/migration/V133__quote_draft_history_evaluation_retry.sql"
    );

    @Test
    void migrationKeepsSnapshotOwnershipRetentionAndGroupingContracts() throws Exception {
        String sql = Files.readString(MIGRATION).replaceAll("\\s+", " ").trim();

        assertThat(sql)
                .contains("CREATE TABLE quote_draft_history_entries")
                .contains("quote_draft_id BIGINT NOT NULL REFERENCES quote_drafts(id) ON DELETE CASCADE")
                .contains("change_group_id UUID NOT NULL")
                .contains("snapshot_payload JSONB NOT NULL")
                .contains("snapshot_fingerprint VARCHAR(64) NOT NULL")
                .contains("now() + interval '30 days'")
                .contains("CREATE UNIQUE INDEX ux_quote_draft_history_change_group")
                .contains("quote_draft_id, change_group_id")
                .contains("CREATE INDEX idx_quote_draft_history_recent");
    }

    @Test
    void evaluationMigrationSeparatesPendingValidAndInvalidHistory() throws Exception {
        String sql = Files.readString(EVALUATION_MIGRATION).replaceAll("\\s+", " ").trim();

        assertThat(sql)
                .contains("evaluation_status VARCHAR(20) NOT NULL DEFAULT 'PENDING'")
                .contains("'PENDING', 'RUNNING', 'VALID', 'INVALID', 'UNAVAILABLE'")
                .contains("evaluation_score INTEGER")
                .contains("evaluation_issue_signature VARCHAR(64)")
                .contains("evaluation_issue_codes JSONB")
                .contains("idx_quote_draft_history_evaluation_pending");
    }

    @Test
    void retryMigrationTracksBoundedEvaluationAttempts() throws Exception {
        String sql = Files.readString(EVALUATION_RETRY_MIGRATION).replaceAll("\\s+", " ").trim();

        assertThat(sql)
                .contains("evaluation_attempts INTEGER NOT NULL DEFAULT 0")
                .contains("evaluation_next_attempt_at TIMESTAMPTZ")
                .contains("evaluation_last_error_code VARCHAR(80)")
                .contains("CHECK (evaluation_attempts >= 0)")
                .contains("evaluation_next_attempt_at, created_at, id");
    }
}
