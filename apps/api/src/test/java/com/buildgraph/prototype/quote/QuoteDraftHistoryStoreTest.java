package com.buildgraph.prototype.quote;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class QuoteDraftHistoryStoreTest {

    @Test
    void convertsPostgresLocalDateTimeAndTextValuesToUtc() {
        OffsetDateTime expected = OffsetDateTime.of(
                2026, 7, 21, 11, 16, 9, 237_713_000, ZoneOffset.UTC);

        assertThat(QuoteDraftHistoryStore.offsetDateTime(
                LocalDateTime.of(2026, 7, 21, 11, 16, 9, 237_713_000)))
                .isEqualTo(expected);
        assertThat(QuoteDraftHistoryStore.offsetDateTime("2026-07-21 11:16:09.237713"))
                .isEqualTo(expected);
    }

    @Test
    void preservesAbsoluteTimeFromJdbcAndOffsetValues() {
        Instant instant = Instant.parse("2026-07-21T11:16:09.237713Z");

        assertThat(QuoteDraftHistoryStore.offsetDateTime(Timestamp.from(instant)).toInstant())
                .isEqualTo(instant);
        assertThat(QuoteDraftHistoryStore.offsetDateTime(
                OffsetDateTime.parse("2026-07-21T20:16:09.237713+09:00")).toInstant())
                .isEqualTo(instant);
    }
}
