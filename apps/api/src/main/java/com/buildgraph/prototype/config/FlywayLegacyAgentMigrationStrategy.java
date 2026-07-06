package com.buildgraph.prototype.config;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class FlywayLegacyAgentMigrationStrategy {
    private static final Logger log = LoggerFactory.getLogger(FlywayLegacyAgentMigrationStrategy.class);

    @Bean
    FlywayMigrationStrategy legacyAgentMigrationStrategy() {
        return flyway -> {
            if (hasLegacyAgentVersionCollision(flyway)) {
                log.warn("Detected legacy agent migration history (dev 계보 V53/V54 또는 main 계보 V56/V57). Running Flyway repair before migrate.");
                flyway.repair();
            }
            flyway.migrate();
        };
    }

    // 같은 agent 마이그레이션이 브랜치 계보에 따라 다른 번호로 적용된 이력을 감지한다.
    // dev 계보는 V53/V54, main 계보는 V56/V57로 적용된 뒤 V69/V70으로 재번호됐다(내용 동일, IF NOT EXISTS 멱등).
    private boolean hasLegacyAgentVersionCollision(Flyway flyway) {
        DataSource dataSource = flyway.getConfiguration().getDataSource();
        String table = flyway.getConfiguration().getTable();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(
                        "SELECT version, description FROM " + table + " WHERE version IN ('53', '54', '56', '57')")) {
            Map<String, String> descriptions = new HashMap<>();
            while (resultSet.next()) {
                descriptions.put(resultSet.getString("version"), resultSet.getString("description"));
            }
            boolean devLineage = "pc agent gold mode contract".equals(descriptions.get("53"))
                    && "agent idempotency records".equals(descriptions.get("54"));
            boolean mainLineage = "pc agent gold mode contract".equals(descriptions.get("56"))
                    && "agent idempotency records".equals(descriptions.get("57"));
            return devLineage || mainLineage;
        } catch (SQLException ex) {
            if ("42P01".equals(ex.getSQLState())) {
                return false;
            }
            throw new IllegalStateException("Failed to inspect Flyway schema history", ex);
        }
    }
}
