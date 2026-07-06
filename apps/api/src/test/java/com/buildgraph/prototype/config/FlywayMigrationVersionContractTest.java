package com.buildgraph.prototype.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class FlywayMigrationVersionContractTest {
    private static final Pattern VERSION_PATTERN = Pattern.compile("^V([^_]+)__.+\\.sql$");
    private static final Path MIGRATION_DIR = Path.of("src/main/resources/db/migration");

    @Test
    void versionedSqlMigrationsUseUniqueFlywayVersions() throws Exception {
        try (var files = Files.list(MIGRATION_DIR)) {
            Map<String, List<String>> namesByVersion = files
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .map(FlywayMigrationVersionContractTest::versionedMigration)
                    .filter(MigrationName::versioned)
                    .collect(Collectors.groupingBy(
                            MigrationName::version,
                            Collectors.mapping(MigrationName::fileName, Collectors.toList())
                    ));

            List<String> duplicateVersions = namesByVersion.entrySet().stream()
                    .filter(entry -> entry.getValue().size() > 1)
                    .sorted(Comparator.comparing(entry -> entry.getKey()))
                    .map(entry -> entry.getKey() + " -> " + entry.getValue())
                    .toList();

            assertThat(duplicateVersions).isEmpty();
        }
    }

    private static MigrationName versionedMigration(String fileName) {
        Matcher matcher = VERSION_PATTERN.matcher(fileName);
        if (!matcher.matches()) {
            return new MigrationName(false, "", fileName);
        }
        return new MigrationName(true, matcher.group(1), fileName);
    }

    private record MigrationName(boolean versioned, String version, String fileName) {
    }
}
