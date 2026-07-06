package com.buildgraph.prototype.part;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * NaverShoppingOfferService 제목 파서 검증 — RAM 모듈 수/총용량/폼팩터, STORAGE 용량.
 * 픽스처는 V19 카탈로그에 실제 유입된 네이버 쇼핑 제목이다.
 * 구현 계약: 파서 입력은 title.toUpperCase(Locale.ROOT) — 각 헬퍼에서 일괄 적용한다.
 */
class NaverShoppingOfferServiceTest {

    private static Integer moduleCount(String title) {
        return NaverShoppingOfferService.parseRamModuleCount(title.toUpperCase(Locale.ROOT));
    }

    private static Integer ramCapacityGb(String title) {
        return NaverShoppingOfferService.parseRamCapacityGb(title.toUpperCase(Locale.ROOT));
    }

    private static String ramFormFactor(String title) {
        return NaverShoppingOfferService.parseRamFormFactor(title.toUpperCase(Locale.ROOT));
    }

    private static Integer storageCapacityGb(String title) {
        return NaverShoppingOfferService.parseStorageCapacityGb(title.toUpperCase(Locale.ROOT));
    }

    // ---- parseRamModuleCount ----

    @Test
    void explicitSingleStickReturnsNull() {
        // "1개"는 개수 표기지만 킷이 아니다(2~8 범위 밖) — null(미기재=단품 1)로 떨어져야 한다.
        assertThat(moduleCount("지스킬 DDR5-6000 CL36 AEGIS 5 32GB, 1개")).isNull();
    }

    @Test
    void serverRamWithoutKitMarkerReturnsNull() {
        // 킷 표기가 전혀 없는 서버용 단품 — 모르는 값을 2로 위장하던 이진 규칙이 없어야 null이다.
        assertThat(moduleCount("삼성전자 서버용 메모리 DDR5 32GB 6400 PC5 51200 ECC REG")).isNull();
    }

    @Test
    void warrantyMonthNotationIsNotAKitCount() {
        // 리뷰 확정 회귀: 'AS 3개월'/'6개월 무이자'는 기간 표기다 — 모듈 수로 오인하면
        // 단품 스틱이 3~6스틱으로 계산돼 슬롯 검사 오탐 FAIL을 만든다.
        assertThat(moduleCount("삼성전자 DDR5 32GB 5600 (정품) AS 3개월")).isNull();
        assertThat(moduleCount("커세어 DDR5 32GB 6000 6개월 무이자")).isNull();
        // 진짜 개수 표기는 여전히 잡는다.
        assertThat(moduleCount("지스킬 DDR5 32GB, 2개")).isEqualTo(2);
    }

    @Test
    void countFirstKitSurvivesRankNotationTrap() {
        // "(2X16GB)"는 count-first 킷 표기로 2 — 랭크 표기 "1RX8"의 X에 걸려 1이나 8이 나오면 안 된다.
        assertThat(moduleCount("삼성 32GB (2X16GB) DDR5 6400MHZ PC5-51200 CSODIMM 1RX8 262핀 노트북"))
                .isEqualTo(2);
    }

    @Test
    void sizeFirstKitWithGOnlyNotationParsesCount() {
        // "(16GX2)" — GB 대신 G만 쓰는 size-first 표기도 킷 2개로 읽는다.
        assertThat(moduleCount("마이크론 CRUCIAL 32GB (16GX2)")).isEqualTo(2);
    }

    @Test
    void countFirstKitWithoutParensParsesCount() {
        // 괄호 없는 "2X16GB"도 count-first로 2.
        assertThat(moduleCount("크루셜 프로 DDR5 6000MHZ 메모리 2X16GB 32GB 키트")).isEqualTo(2);
    }

    @Test
    void koreanCountWithGbTokenParsesCount() {
        // 한국어 개수 표기 "2개"는 GB 토큰이 함께 있을 때만 킷 개수로 인정한다.
        assertThat(moduleCount("지스킬 32GB, 2개")).isEqualTo(2);
    }

    @Test
    void koreanCountInsideKitParensParsesCount() {
        // "키트(32GB 2개)" — 괄호 안 한국어 개수 표기. "(PC55200)" 잡음에 흔들리면 안 된다.
        assertThat(moduleCount("팀그룹 64GB 키트(32GB 2개) 6400MHZ(PC55200)")).isEqualTo(2);
    }

    // ---- parseRamCapacityGb ----

    @Test
    void ramCapacityIsMaxGbToken() {
        // "32GB (2X16GB)"에서 GB 토큰은 32와 16 — max 규칙으로 총용량 32를 취한다.
        assertThat(ramCapacityGb("삼성 32GB (2X16GB) DDR5 6400MHZ PC5-51200 CSODIMM 1RX8 262핀 노트북"))
                .isEqualTo(32);
    }

    @Test
    void ramCapacityIgnoresGOnlyModuleNotation() {
        // "64GB(32GX2)" — GB 토큰은 64뿐이고 "32G"는 GB 토큰이 아니다 → 총용량 64.
        assertThat(ramCapacityGb("팀그룹 T-CREATE 64GB(32GX2)")).isEqualTo(64);
    }

    @Test
    void countFirstKitWithoutTotalTokenUsesKitProduct() {
        // 리뷰 확정 회귀: 총량 토큰 없는 '2X16GB' 표기 — GB 토큰(16)만 취하면 킷 총용량이
        // 절반으로 기재돼 moduleCount=2와 자기모순이 된다. 곱셈값(32)과 max 비교해야 한다.
        assertThat(ramCapacityGb("커세어 DDR5 6000 2X16GB 킷")).isEqualTo(32);
        assertThat(ramCapacityGb("삼성 DDR5 5600 (2X24GB)")).isEqualTo(48);
    }

    @Test
    void nonBinaryCapacitySurvives() {
        // 48GB(24Gx2 킷) — 2의 거듭제곱만 통과시키던 이진 규칙이 제거됐는지 검증한다.
        assertThat(ramCapacityGb("커세어 VENGEANCE DDR5 6000MHZ CL30 48GB")).isEqualTo(48);
    }

    @Test
    void ramCapacityWithoutGbTokenReturnsNull() {
        // GB 표기가 전혀 없으면 용량 미기재 — 소비처가 toolReady=false로 제외하도록 null.
        assertThat(ramCapacityGb("삼성전자 DDR5 6400 PC5 51200 메모리")).isNull();
    }

    // ---- parseRamFormFactor ----

    @Test
    void laptopSodimmTitleParsesAsSodimm() {
        // "CSODIMM"은 SODIMM을 부분 문자열로 포함하고 "노트북"도 있다 — 데스크탑 검사가 차단하게 SODIMM으로 기록.
        assertThat(ramFormFactor("삼성 32GB (2X16GB) DDR5 6400MHZ PC5-51200 CSODIMM 1RX8 262핀 노트북"))
                .isEqualTo("SODIMM");
    }

    @Test
    void eccRegisteredTitleParsesAsRdimm() {
        // 서버용 "ECC REG" 표기는 RDIMM.
        assertThat(ramFormFactor("삼성전자 서버용 메모리 DDR5 32GB 6400 PC5 51200 ECC REG")).isEqualTo("RDIMM");
    }

    @Test
    void plainDesktopTitleDefaultsToUdimm() {
        // 노트북/서버 표기가 없는 일반 데스크탑 제목은 기본 UDIMM.
        assertThat(ramFormFactor("지스킬 DDR5-6000 CL36 AEGIS 5 32GB, 1개")).isEqualTo("UDIMM");
    }

    // ---- parseStorageCapacityGb ----

    @Test
    void storageTbConvertsToDecimalGb() {
        // TB는 십진 환산(8TB=8000) — 기존 4TB=4000 관례를 따른다.
        assertThat(storageCapacityGb("킹스톤 FURY RENEGADE G5 M.2 NVME 8TB")).isEqualTo(8000);
    }

    @Test
    void storageCapacitySurvivesSn8100ModelNumberTrap() {
        // 모델명 "SN8100"의 8100에 걸리지 않고 "1TB"만 용량으로 읽어야 한다.
        assertThat(storageCapacityGb("샌디스크 WD BLACK SN8100 M.2 NVME SSD 1TB")).isEqualTo(1000);
    }

    @Test
    void storageCapacitySurvivesSamsungPartNumberTrap() {
        // 품번 "MZ-VAP2T0CW"의 "2T0"은 TB 토큰이 아니다 — "2TB"만 용량으로 읽어 2000.
        assertThat(storageCapacityGb("삼성 990 EVO 2TB MZ-VAP2T0CW")).isEqualTo(2000);
    }

    @Test
    void storageWithoutCapacityTokenReturnsNull() {
        // 규격("M.2 2280 GEN5.0")만 있고 GB/TB 표기가 없으면 용량 미기재 — null.
        assertThat(storageCapacityGb("패트리어트 VIPER PV553 M.2 2280 GEN5.0")).isNull();
    }

    @Test
    void storageGbTokenParsesDirectly() {
        // GB 단위 표기는 그대로 GB 값이다.
        assertThat(storageCapacityGb("삼성전자 980 M.2 NVME 512GB")).isEqualTo(512);
    }
}
