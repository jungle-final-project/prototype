package com.buildgraph.prototype.assembly;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AssemblyPaymentServiceTest {

    @Test
    void convertsJdbcTimestampWithoutReparsingItsDisplayText() {
        Timestamp timestamp = Timestamp.valueOf("2026-07-20 21:21:32.123456");

        OffsetDateTime result = AssemblyPaymentService.offsetDateTime(timestamp);

        assertThat(result.toInstant()).isEqualTo(timestamp.toInstant());
    }

    @Test
    void acceptsOffsetAndOffsetlessTimestampStrings() {
        assertThat(AssemblyPaymentService.offsetDateTime("2026-07-20T21:21:32+09:00"))
                .isEqualTo(OffsetDateTime.parse("2026-07-20T21:21:32+09:00"));
        assertThat(AssemblyPaymentService.offsetDateTime("2026-07-20 21:21:32"))
                .isEqualTo(LocalDateTime.parse("2026-07-20T21:21:32").atOffset(ZoneOffset.UTC));
    }
}
