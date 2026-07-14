package com.buildgraph.prototype.agent.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PcAgentDiagnosisAsRequestMigrationContractTest {
    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V122__pc_agent_diagnosis_as_requests.sql"
    );

    @Test
    void migrationExtendsExistingTicketWithDiagnosisAsRequestContract() throws Exception {
        String sql = Files.readString(MIGRATION).replaceAll("\\s+", " ").trim();

        assertThat(sql)
                .contains("CREATE SEQUENCE IF NOT EXISTS as_ticket_request_number_seq")
                .contains("CREATE TABLE IF NOT EXISTS pc_agent_diagnosis_requests")
                .contains("diagnosis_id UUID PRIMARY KEY")
                .contains("requested_checks JSONB NOT NULL")
                .contains("jsonb_typeof(requested_checks) = 'array'")
                .contains("expires_at > requested_at")
                .contains("mode VARCHAR(10) NOT NULL CHECK (mode IN ('LIVE', 'DEMO'))")
                .contains("ALTER TABLE as_tickets")
                .contains("ADD COLUMN IF NOT EXISTS diagnosis_id UUID")
                .contains("ADD COLUMN IF NOT EXISTS agent_device_id BIGINT REFERENCES agent_devices(id)")
                .contains("ADD COLUMN IF NOT EXISTS request_number VARCHAR(40)")
                .contains("ADD COLUMN IF NOT EXISTS request_type VARCHAR(50)")
                .contains("ADD COLUMN IF NOT EXISTS evidence_summary JSONB")
                .contains("ADD COLUMN IF NOT EXISTS diagnosis_result JSONB")
                .contains("ADD COLUMN IF NOT EXISTS diagnosis_consent_accepted_at TIMESTAMPTZ")
                .contains("ux_as_tickets_diagnosis_id")
                .contains("WHERE diagnosis_id IS NOT NULL")
                .contains("ux_as_tickets_request_number")
                .contains("diagnosis_mode IN ('LIVE', 'DEMO')")
                .contains("request_type IN ('PHYSICAL_INSPECTION')")
                .doesNotContain("CREATE TABLE agent_as_requests");
    }
}
