package com.buildgraph.prototype.part.manufacturer;
import com.buildgraph.prototype.part.price.NaverShoppingOfferService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.buildgraph.prototype.agent.OpenAiResponsesClient;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class ManufacturerReleaseIntakeServiceTest {
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final NaverShoppingOfferService naverShoppingOfferService = mock(NaverShoppingOfferService.class);
    private final OpenAiResponsesClient openAiResponsesClient = mock(OpenAiResponsesClient.class);
    private final ManufacturerReleaseIntakeService service = new ManufacturerReleaseIntakeService(
            jdbcTemplate,
            naverShoppingOfferService,
            openAiResponsesClient,
            "BuildGraphTest/0.1",
            10_000L,
            20_000L
    );

    @Test
    void cleanTextUnwrapsCdataBeforeStrippingTags() {
        // 실제 제조사/워드프레스 계열 RSS는 title을 CDATA로 감싼다. 예전 정규식은 CDATA 블록
        // 전체를 삼켜 제목이 빈 문자열이 되고 전 게시물이 미탐됐다(감사 A2).
        assertThat(ManufacturerReleaseIntakeService.cleanText("<![CDATA[ASUS ROG RTX 5090 발표]]>"))
                .isEqualTo("ASUS ROG RTX 5090 발표");
        assertThat(ManufacturerReleaseIntakeService.cleanText("<![CDATA[멀티라인\n제목]]>"))
                .isEqualTo("멀티라인 제목");
        assertThat(ManufacturerReleaseIntakeService.cleanText("<b>태그</b> &amp; 엔티티"))
                .isEqualTo("태그 & 엔티티");
    }

    @Test
    void parsePublishedAtSupportsRfc1123RssDates() {
        // RSS 표준 pubDate(RFC-1123)와 ISO 둘 다 파싱돼야 한다(감사 A2).
        assertThat(ManufacturerReleaseIntakeService.parsePublishedAt("Wed, 01 Jul 2026 09:00:00 GMT"))
                .isEqualTo(OffsetDateTime.parse("2026-07-01T09:00:00Z"));
        assertThat(ManufacturerReleaseIntakeService.parsePublishedAt("2026-07-01T09:00:00+09:00"))
                .isEqualTo(OffsetDateTime.parse("2026-07-01T09:00:00+09:00"));
        assertThat(ManufacturerReleaseIntakeService.parsePublishedAt("not-a-date")).isNull();
    }

    @Test
    void postContentHashUsesSingleFormulaWithNullSafeExcerpt() {
        // draft(HTML 2요소)와 관리자 updatePost(3요소)의 해시 공식 불일치로 관리자 수정 후
        // 영구 contentChanged 오판이 났다(감사 A1). 단일 공식 + null excerpt = "" 를 보장한다.
        assertThat(ManufacturerReleaseIntakeService.postContentHash("t", "u", null))
                .isEqualTo(ManufacturerReleaseIntakeService.postContentHash("t", "u", null))
                .isNotEqualTo(ManufacturerReleaseIntakeService.postContentHash("t", "u", "excerpt"));
    }

    @Test
    void createSourceRejectsNonOfficialManufacturerDomainBeforeDatabaseWrite() {
        assertThatThrownBy(() -> service.createSource(Map.of(
                "manufacturer", "ASUS",
                "categoryScope", "GPU",
                "sourceType", "NEWS",
                "sourceUrl", "https://example.com/asus-news",
                "enabled", true
        )))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
