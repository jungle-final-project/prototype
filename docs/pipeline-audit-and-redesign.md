# 파이프라인 검증 리포트 + 개선 설계서

> 대상: (A) 제조사 릴리스 → 자산 인테이크 파이프라인, (B) 사용자 활동·AS → XGBoost 추천 파이프라인
> 목적: **검증(무엇이 실제로 동작하고 무엇이 깨졌는가) + 우선순위 개선 설계**. 구현은 별도 세션에서 진행.
> 기준: 로컬 `main`(= PR #61 슬롯보드 + PR #60까지 병합, #62 제외), 2026-07-05.

> **구현 상태 (2026-07-06 갱신)** — 브랜치 `claude/pipeline-hardening`(-p1/-p2), 커밋 6d55466 → 9ad000a:
> **P0 전체(P0-1~P0-9) ✅ · P1 중 B9/O6/O4/B6/B7/A5 ✅ · P2 중 A9(조건부 GET+poll_interval)/O7(advisory lock)/B11(리랭커 관측성) ✅ + 계획 외 차단 자동 철수(V92) 추가 구현.**
> 미착수/보류(정책 결정 필요): canonical dedup, 자동 retraining cadence, 온라인 A/B, B10 shadow 비교 대시보드, O5 price_jobs 좀비 스위퍼, A6~A8. B8은 부분 해소(holdout 지표 존재 게이트까지 — '기존 모델 대비 우위' 정량 비교 게이트는 미구현).
> 이 중 추천 계열 보류 항목(자동 retraining, 정량 승급 게이트, B10, 온라인 A/B, drift 감지)의 **구체 설계는 `docs/mlops-maturity-design.md`(L1→L2 로드맵, 3렌즈 적대적 리뷰 반영)로 확정**했다 — 구현 착수 시 그 문서를 기준으로 한다.
> 아래 본문은 **구현 전(2026-07-05) 감사 스냅샷 원형을 보존**하며, 해소된 항목에는 짧은 각주만 덧붙였다. 세부는 §7 구현 상태 표 참조.

## 0. 검증 방법론

세 개의 독립 소스를 교차했습니다.

1. **병렬 코드 감사** — 7개 서브시스템을 독립 에이전트가 실제 코드 기준으로 독해, HIGH 발견은 "반박하라"는 적대적 검증 통과. 결과: HIGH 19 / MEDIUM 36 / LOW 19. 적대적 검증에서 **11 CONFIRMED, 1 PARTIAL, 0 REFUTED**(반박된 것 없음).
2. **런타임 프로브** — Docker 스택을 띄우고 관리자 API·리랭커 컨테이너를 직접 호출해 실제 상태 관측.
3. **외부 리서치 대조** — ChatGPT 리서치 보고서 2건을 코드로 재검증(맞은 주장/틀린 주장 분리).

## 1. 한 줄 결론

**두 파이프라인 모두 구성요소·스키마·상태기계는 잘 설계돼 있으나, (1) 한 번도 E2E로 완주된 적이 없고, (2) 런타임 어댑터·평가·관측이 스키마보다 한참 뒤처져 있으며, (3) "성공처럼 보이지만 실제로는 아무것도 안 한" 침묵 실패 경로가 다수 존재한다.** 지금 필요한 건 더 똑똑한 모델이 아니라 **정확성·재현성·관측성 가드레일**이다.

> (2026-07-06) 위 결론은 구현 전 스냅샷이다. 커밋 7685643에서 Docker E2E가 사상 처음 완주됐고(이벤트 수집 → dataset lock → 워커 훈련·holdout 기록 → activate → 홈 `scoreSource=XGBOOST` 서빙 → retire 복귀), 침묵 실패 경로(A2/O2/O3)는 제거됐다.

## 2. 런타임 실측 스냅샷 (E2E 미완주 증거)

| 프로브 | 관측값 | 의미 |
|---|---|---|
| `manufacturer_sources` | 10개(9 enabled), `lastCheckedAt` **전부 null** | 자동 스캔이 **한 번도 실행된 적 없음** (스케줄러 기본 OFF) |
| `manufacturer-posts` | 0건 | 수집된 게시물 0 |
| `recommendation-training/overview` | eligibleEvents 144, **trainedDistinctEvents 0**, **asFeedbackEvents 0** | 학습 **한 번도 실행 안 됨**, AS 신호 유입 0 |
| `recommendation-models/summary` | activeModel **null**, latestModel `artifactPath: null` status `SHADOW` | 실제 훈련 산출물 없음, seed 껍데기만 |
| home 추천 scoreSources | **FALLBACK 100%** (144/144) | XGB 점수가 실제로 안 쓰임 |
| xgb-reranker `/health` | UP, **`modelLoaded: false`** | 컨테이너는 뜨나 모델 미로드 |
| xgb-reranker 로그 | **0줄** | 관측성 전무 |

→ "돌아가는 것처럼 보이지만" 두 파이프라인 모두 **의미 있는 데이터가 흐른 적이 없다**. 데모/검증 전에 최소 1회 E2E 완주 + 시드가 선행돼야 한다.

> (2026-07-06) 위 표는 **2026-07-05 구현 전 스냅샷**이다. 구현 후 재실측: E2E 최초 완주(7685643), 학습 워커가 `metrics.holdout`(NDCG@4·Spearman·MAE/RMSE) 기록, 실모델 activate 후 홈 `scoreSource=XGBOOST` 서빙 확인, 홈 응답 0.49s→0.038s(12배, a4d1e3b), 리랭커 `/health`에 카운터 노출(9ad000a).

## 3. ChatGPT 리서치 보고서 대조 (코드 재검증)

사용자 제공 보고서 2건을 실제 코드로 확인한 결과입니다. **보고서2(최신)가 대체로 정확**하고, **보고서1은 구버전 기준 오판이 섞여** 있습니다.

| 보고서 주장 | 판정 | 근거 |
|---|---|---|
| (R1) "scorer 서비스가 compose에 없음" — P0 리스크 4위 | ❌ **오판** | `compose.yaml`에 `xgb-reranker`(8091, healthcheck, api `service_healthy` 의존) 완비. 컨테이너 UP 확인. 보고서1이 본 트리가 구버전. |
| (R1/R2) demo-agent-activation-token 하드코딩 | ✅ 확인 | `PcAgentAsService.java:31` `DEMO_ACTIVATION_TOKEN`. 단 DB 발급 토큰(`AgentActivationTokenEntity`)과 **공존**하는 데모 폴백 형태 — "DB 토큰화가 전무"는 과장. *(당시 확인 — 이후 `agent.demo-activation-token` 프로퍼티 게이트로 해소, P0-9/6d55466)* |
| (R2) 훈련 워커가 in-sample MAE/RMSE만 기록 | ✅ 확인(핵심) | `tools/reranker_service.py:357-360` fit 직후 동일 x로 predict. holdout/랭킹지표 전무. *(당시 확인 — 이후 시간 기반 holdout+NDCG@4로 교체, P0-4/7685643)* |
| (R2) AS 라벨 브리지 `LIMIT 1` 조인 모호 | ✅ 확인 | `RecommendationLearningService.java:218`. |
| (R2) AI 초안 실행이 곧바로 INACTIVE 자산 승인까지 자동 체이닝 | ✅ 확인 | `ManufacturerReleaseIntakeService.java:741` → `approveCatalogCandidateAsInactive` 직접 호출. |
| (R1/R2) ETag/Last-Modified 저장만, 조건부 GET 미활용 | ✅ 확인 | 저장 `:1533-1545`, 요청 헤더에 미전송 `:1348-1362`. *(당시 확인 — 이후 조건부 GET 전송+304 처리 구현, A9/9ad000a)* |
| (R2) 최소 학습 행 수 가드 = worker에서 50행 | ✅ 확인(정정) | 서버 `createJob`엔 없고, **워커**가 `SKIPPED_LOW_DATASET`(50행)로 게이트. compose `RECOMMENDATION_TRAINING_MIN_ROWS=50`. |
| (R2) RERANKER_ENABLED 기본 false / SHADOW true | ✅ 확인 | compose 기본값과 일치 — 온라인 순위 적용은 보수적으로 막음. |
| (R1) "AS summary adapter 공백" | ✅ 부분확인 | 업로드 경로가 `agent_log_summaries` 생성을 안 함 — 요약 생성은 별도/비동기 경로. |

> **보고서가 놓친 것**: 두 보고서 모두 (a) **RSS CDATA 미지원으로 실제 피드 전량 미탐**, (b) **재스캔이 관리자/AI 검토를 무음 덮어씀**, (c) **학습-서빙 피처 스큐**(price_age_days 999↔0), (d) **as_* 20개 피처가 학습에서 통째 무시**, (e) **FALLBACK 판정이 거짓**(baseline 점수가 실제 순위 결정) 을 못 잡았습니다. 이들은 아래 P0의 핵심이며, 외부 리서치보다 코드 대조 감사가 더 깊게 짚은 지점입니다.

---

## 4. 파이프라인 A (자산 인테이크) — 검증 결과

### 4.1 실제 동작 흐름 (요약)

기본 OFF(`part.manufacturer-release-intake.enabled=false`). 켜지면 매일 06:00 KST `scanAll(20,true)` →
소스 fetch → **본문 전체 SHA-256** 비교(같으면 스킵) → 변경 시 RSS(`<item>` 정규식)/HTML(`<a href>` 폴백) 파싱 →
게시물 upsert(`(source_id, external_url)` 유일성) → **룰 기반** classify(PENDING/PRODUCT_CANDIDATE/IGNORED) →
PRODUCT_CANDIDATE면 네이버쇼핑 검색으로 `part_catalog_candidates` 생성.
LLM 분류·스펙 자동 채움은 **관리자가 게시물 단위로 수동 실행**하는 `createAiAssetDraftForPost`에서만.

### 4.2 확정 결함

| # | 심각도 | 결함 | 증거 | 검증 |
|---|---|---|---|---|
| A1 | **P0** | **재스캔이 관리자/AI 검토 결과를 무음 덮어씀.** content_hash 변경 시 `raw_payload`를 병합(`\|\|`) 아닌 **전체 교체**(`?::jsonb`)해 adminReview·aiAssetDraft·naverCandidate 이력이 전부 소실. 게다가 draft 해시(title\|url 2요소)와 관리자 updatePost 해시(title\|url\|excerpt 3요소)가 **영구 불일치** → 관리자가 IGNORED 처리한 게시물이 다음 스캔에 PRODUCT_CANDIDATE로 **부활** + 후보 재생성. 검토 큐 무한 재오염. | `ManufacturerReleaseIntakeService.java:960-986, :1413, :556, :373-379` | ✅ CONFIRMED **→ ✅ 해소**(62a68eb — V90 `classification_source` 검토 잠금+해시 통일) |
| A2 | **P0** | **RSS CDATA 미지원 → 실제 제조사 피드 전량 미탐이 "성공"으로 기록.** `cleanText`의 `<[^>]+>` 정규식이 `<![CDATA[제목]]>` 전체를 삼켜 title이 빈 문자열 → 모든 item skip. drafts=0이어도 `last_content_hash` 저장하고 성공 처리(침묵 실패). pubDate도 ISO만 지원해 RFC-1123(`Tue, 01 Jul 2026 …`)은 파싱 실패. **데모 피드(ISO+CDATA 없음)만 통과하고 실전은 실패하는 구조.** | `ManufacturerReleaseIntakeService.java:1895-1907, :1373-1393, :1630-1639` | ✅ CONFIRMED **→ ✅ 해소**(62a68eb — CDATA 언랩+RFC-1123 폴백) |
| A3 | **P0** | **AI 초안 실행이 곧바로 INACTIVE 자산 초안 연결까지 자동 체이닝.** `createAiAssetDraftForPost`가 AI 분류→후보 생성/동기화→`approveCatalogCandidateAsInactive`까지 한 번에 호출. 잘못된 분류/불완전 스펙이 검수 게이트 없이 내부 자산으로 전파. (양 GPT 보고서 P0 합의) | `ManufacturerReleaseIntakeService.java:741` | ✅ 코드확인 **→ ✅ 해소**(62a68eb — AI 초안 자동 승인 분리) |
| A4 | **P0** | **후보 수정/재승인·offer 재검색 시 관리자 수동 입력 part 스펙 / AI 추출 스펙이 통째로 소실**(raw_payload 전체 교체 계열). | `NaverShoppingOfferService` withReleaseContext(450-457), `PartController.java:472-479` | ✅ CONFIRMED **→ ✅ 해소**(62a68eb) |
| A5 | P1 | **후보 생성 graceful 실패 시 사실상 미생성.** 네이버 키 미설정/장애/오퍼없음이 예외 아닌 `created=false` → 그래도 스캔 성공 처리·해시 저장. 복구 후 게시물이 피드/스캔한도(20) 밖으로 밀리면 영구 미생성. | `NaverShoppingOfferService.java:136-142, :645-647`, `ManufacturerReleaseIntakeService.java:373-396` | ⚠️ PARTIAL(범위 좁힘) **→ ✅ 해소**(ab868bb — scanAll 말미 candidateBackfill 재시도) |
| A6 | P1 | **HTML 폴백 파서 광범위 오탐.** 모든 `<a>` 대상 + `"NEW"` 부분문자열 매칭("News/Newsroom"도 통과) → 내비/푸터 링크가 제품 후보로 유입. | `:1395-1417, :1580-1596` | 감사 **(미착수 — 현행 유효)** |
| A7 | P1 | **원문 스냅샷 미보존** — 승인 근거의 사후 재검증 불가(raw_payload가 교체되므로). | 감사 | 미검증 **(미착수 — 현행 유효)** |
| A8 | P1 | **제목 키워드 하드코딩 스펙이 검증 없이 자산 속성으로 확정** 가능성. | 감사 | 미검증 **(미착수 — 현행 유효)** |
| A9 | P2 | ETag/Last-Modified·poll_interval_minutes가 죽은 설정 — 매일 전 소스 전체 다운로드. | `:1348-1362, :313-345` | 감사 **→ ✅ 해소**(9ad000a — If-None-Match/If-Modified-Since 전송+304 처리, scanAll이 poll 주기 실제 적용) |

> (2026-07-06 추가) 계획에 없던 후속 구현: **차단 자동 철수** — 403/429 차단 응답이 연속 3회 누적되면 source를 `ERROR`가 아닌 `PAUSED`로 자동 전환하고 수동 재개를 요구한다(V92 `consecutive_failures`, 9ad000a).

### 4.3 보안: 인증 없는 데모 엔드포인트
`/api/demo/manufacturer-release-feed.xml`이 인증 없이 공개(`ManufacturerReleaseDemoController`), 유일한 SecurityFilterChain은 `/api/agent/**` 전용. 프로덕션 빌드에도 무조건 배포·공개되고, 피드가 가리키는 게시물 링크는 핸들러 없는 데드 링크. → **P1: 데모 컨트롤러를 프로파일 게이트(`@Profile("demo")`)로 격리.**

> (2026-07-06) ✅ 해소 — 단, `@Profile` 대신 **프로퍼티 게이트** 채택: 데모 피드는 `part.manufacturer-release-intake.demo-feed-enabled`(기본 false, `@ConditionalOnProperty`)일 때만 라우트가 등록된다(a4d1e3b). "프로덕션 무조건 공개"는 더 이상 사실이 아니다.

---

## 5. 파이프라인 B (추천) — 검증 결과

### 5.1 실제 동작 흐름 (요약)

이벤트(`recommendation_events`, label_score) → 관리자가 dataset 생성/LOCK → training job(QUEUED) →
xgb-reranker 워커가 `FOR UPDATE SKIP LOCKED`로 큐 소비, 50행 미만이면 `SKIPPED_LOW_DATASET`, 충분하면 XGBRegressor 학습 → SHADOW 모델 → 관리자 수동 activate.
홈 추천은 `/score` 호출, 실패/baseline-shadow/예외 시 FALLBACK. 온라인 rerank는 기본 OFF, shadow만 ON.

### 5.2 확정 결함 (ML 핵심 — 외부 리서치가 놓친 지점 다수)

| # | 심각도 | 결함 | 증거 | 검증 |
|---|---|---|---|---|
| B1 | **P0** | **학습 평가가 100% in-sample.** fit 직후 동일 데이터로 predict해 MAE/RMSE만 기록. holdout/time-split/NDCG/AUC/early-stopping 전무. 이 지표가 `model_versions.metrics`에 저장돼 **일반화 성능을 전혀 반영 못 함** → SHADOW 품질 오판. | `tools/reranker_service.py:357-360, :365-373` | ✅ CONFIRMED **→ ✅ 해소**(7685643 — 시간 기반 80/20 holdout+NDCG@4+early stopping) |
| B2 | **P0** | **학습-서빙 피처 스큐.** `part_price_age_days` 결측 기본값이 **훈련 999 vs 서빙 0**(정반대). `rank_position` 의미 불일치. 학습·서빙 피처가 **다른 코드**(Java 스냅샷 vs Python 서빙)에서 계산돼 정합 보장 없음. 모델이 학습한 것과 다른 입력으로 추론 → 조용한 성능 붕괴. | `RecommendationTrainingService.java:543, :470-473`, `reranker_service.py:348` | ✅ CONFIRMED **→ ✅ 해소**(7685643 — price_age_days 999 통일, rank_position은 포지션 누수로 피처 제외) |
| B3 | **P0** | **as_\* 피처 20개가 학습에서 통째 무시.** 스냅샷은 as_line_count·as_thermal_risk·as_severity_\*·as_failure_\* 등 20개를 정성껏 기록하지만 Python `FEATURES` 배열에 없어 **dead feature**. → AS→XGB의 핵심 가치(AS 신호로 추천 개선)가 **실제로는 라벨 노이즈로만 작용**. | `RecommendationTrainingService.java:548-562` vs `reranker_service.py` FEATURES | ✅ CONFIRMED **→ ✅ 해소**(7685643 — as_*를 features_snapshot에서 분리해 `eventSnapshot.asContext`로 보존. FEATURES 편입이 아닌 **제거안** 채택) |
| B4 | **P0** | **FALLBACK 판정이 거짓.** `scoreCandidates`가 scorer 응답 루프에서 Java deterministicScore를 무조건 덮어쓴 뒤에야 baseline-shadow 검사로 FALLBACK 리턴 — **덮어쓴 baseline 점수를 복원 안 함**. 즉 `scoreSource=FALLBACK`이라 표시돼도 실제 순위는 **Python baseline 점수**가 결정. 관측 지표가 거짓말. | `HomePartRecommendationService.java:203, :234-237`, `reranker_service.py:80-88` | ✅ CONFIRMED **→ ✅ 해소**(7685643 — scoreSource와 실제 정렬 점수 출처 항상 일치) |
| B5 | **P0** | **학습 이벤트 사용자 위조 가능(데이터 포이즈닝).** `recordEvent`가 `sourceSurface`를 클라이언트 자유 문자열로 저장(화이트리스트 없음), eventType 방어는 AS/ADMIN 접두사만 차단해 `ORDER_INTENT`(+5.0) 자유 허용. 인증된 아무 사용자나 임의 부품에 고가중 positive 라벨 주입 가능. (완화: 학습은 관리자 LOCK 필요, but datasetItems included 기본 true라 무검토 진행 시 포함) | `RecommendationLearningService.java:65, :44-49` | ✅ CONFIRMED **→ ✅ 해소**(7685643 — sourceSurface 화이트리스트 `BUILD_CHAT`/`HOME_RECOMMENDED_PARTS`, 위반 400) |
| B6 | P1 | **AS 라벨 정정이 학습 이벤트에 미반영.** `as_ticket_labels`는 재확정 허용하나 이벤트 idempotencyKey가 `AS_CONFIRMED_NEGATIVE:{ticketId}`로 고정 → 관리자가 라벨을 고쳐도 기존 이벤트의 part_id/label 갱신 안 됨. **잘못된 부품에 -2.0이 영구 잔존.** | `RecommendationLearningService.java:107, :229-239` | ✅ CONFIRMED **→ ✅ 해소**(ab868bb — 재확정 시 기존 이벤트의 part/build/recommendation 링크 갱신) |
| B7 | P1 | **AS 라벨 브리지 `LIMIT 1` 모호.** 티켓당 여러 업로드/재요약 생기면 `agent_log_summaries WHERE as_ticket_id=? LIMIT 1` 조인이 잘못된 요약과 연결. (GPT-R2 지적, 확인) | `:218` | ✅ 코드확인 **→ ✅ 해소**(ab868bb — `logSummaryId` 명시 지정, 타 티켓 소속이면 400, 생략 시 최신 요약) |
| B8 | P1 | **승급 거버넌스 부재.** SHADOW 생성은 자동이나 ACTIVE 승급 기준(holdout 지표·기존 대비 우위·롤백)이 코드에 없음. 관리자가 지표 해석 없이 activate하면 곧바로 홈 실순위 반영. | 설계 갭 | 감사 **→ ⚠️ 부분 해소**(7685643 — `metrics.holdout` 없는 모델은 activate 409 거절. '기존 모델 대비 우위' 정량 비교·롤백 기준은 미구현) |

### 5.3 서빙 성능/관측 결함

| # | 심각도 | 결함 | 증거 |
|---|---|---|---|
| B9 | P1 | **홈 요청마다 전체 후보 동기 스코어링 + 후보 수만큼 shadow INSERT.** 홈 로딩이 scorer 응답(≤1.2s)에 블로킹되고 `recommendation_shadow_scores`가 요청당 N행 무한 성장(retention/샘플링 없음). request_hash가 이미 있으니 캐시·dedup 가능한데 미활용. **→ ✅ 해소**(a4d1e3b — tri-state 서빙: baseline 확정 시 비동기 shadow 기록만, request_hash당 기본 5분 스로틀, 30일 retention 스케줄러. 홈 응답 0.49s→0.038s 실측) | `HomePartRecommendationService.java:54, :193-205` |
| B10 | P1 | **섀도우 "비교" 자동화 부재.** rank_position·raw_response만 쌓고 역전율/NDCG 비교 지표를 계산·저장·대시보드화하는 코드 없음 → shadow 모드 목적(모델이 더 나은가) 달성 수단이 수작업 SQL뿐. **(미착수 — shadow 데이터가 이제 스로틀·보존 정책 하에 쌓이므로 비교 지표 자동화가 다음 단계)** | 설계 갭 |
| B11 | P2 | 리랭커 컨테이너 관측성 전무(액세스 로그 억제, 메트릭 endpoint 없음, healthcheck가 `modelLoaded:false`를 healthy로 간주). **→ ✅ 해소**(9ad000a — `/health`에 요청/오류/리로드 카운터 노출, malformed 요청 400 JSON) | `reranker_service.py:231-232` |

---

## 6. 공통 운영 결함

| # | 심각도 | 결함 | 증거 | 검증 |
|---|---|---|---|---|
| O1 | **P0** | **단일 스케줄러 스레드 + fetch 무한 타임아웃.** `@EnableScheduling`만 있고 pool 설정 없음 → 기본 1스레드에 6개 잡 직렬. 제조사 RestClient는 타임아웃 미설정 → 소스 1곳 hang 시 이후 **모든 cron(가격 갱신)·프리웜·티어 스냅샷 정지**, 재시작 전까지 신호 없음. | `PrototypeApplication.java:8`(pool 설정 부재), `ManufacturerReleaseIntakeService.java:91-93,1348-1352` | ✅ 자체확인 **→ ✅ 해소**(6d55466 — `scheduling.pool.size=4`, connect/read 10s/20s 타임아웃) |
| O2 | **P0** | **네이버 API 장애 전면 silent.** `fetchOffers`가 4xx/5xx/429 예외를 삼키고 빈 리스트 반환 → 잡이 `updated=0`으로 "정상 종료", **parts.price가 몇 주간 정체돼도 무신호**. 요청 간 지연 0이라 429 확률도 높음. | `NaverShoppingOfferService.java:645-647, :555-590` | ✅ 자체확인 **→ ✅ 해소**(6d55466 — 4xx/5xx/429를 `errors`로 구분 집계+백오프+연속 오류 조기 중단) |
| O3 | **P0** | **관리자 "가격 작업 실행"이 실제로는 첫 20행(사실상 CASE만) force 갱신 후 SUCCEEDED 표시.** `refreshOffers(null,null,true)` → limit 기본 20 + `ORDER BY category,id` → GPU/CPU 가격은 하나도 안 바뀜. danawa OFF 플래그도 이 경로는 무시. | `PriceJobWorker.java:31-32`, `NaverShoppingOfferService.java:482,516` | ✅ 자체확인 **→ ✅ 해소**(6d55466 — worker가 `refreshDailyOffers()` 전 카테고리 순회) |
| O4 | P1 | 스케줄 실행 결과가 로그 한 줄뿐 — 실패해도 관리자 UI 무신호(릴리스 스캔은 화면에 아예 없음). | 4개 스케줄러 `:22-25` | 감사 **→ ✅ 해소**(ab868bb — V91 `pipeline_job_runs` + `GET /api/admin/pipeline-job-runs` + AdminPriceJobsPage 자동 실행 이력 패널) |
| O5 | P1 | price_jobs 좀비 QUEUED/RUNNING이 수동 가격 작업 영구 잠금(publish 실패 시 보상 없음, stale 스위퍼 없음). | `PriceQueryService.java:86-100` | 감사 **(미착수 — 유일한 잔존 운영 결함)** |
| O6 | P1 | **데모 중 데이터 변동 리스크.** price-refresh 기본 ON(네이버 키 있으면 04:00에 parts.price 변경) + 관리자 버튼 즉시 mutate + 티어 스냅샷 1시간 재계산 → 데모 중 같은 질문에 다른 추천. 단일 freeze 플래그 없음. | `application.yml:166`, `BuildChatTierSnapshotRefresher.java:55` | 감사 **→ ✅ 해소**(a4d1e3b — `DEMO_FREEZE_MUTATIONS` 단일 스위치: 스케줄러 4종 `SKIPPED_FROZEN` + 가격 Job 409) |
| O7 | P2 | 분산 잠금(ShedLock) 없음 — API 2인스턴스로 늘리면 모든 크론 중복 실행. | 설계 갭 | 감사 **→ ✅ 해소**(9ad000a — ShedLock 대신 새 의존성 없는 PG advisory lock, 미획득 시 `SKIPPED_LOCKED` 이력) |

---

## 7. 우선순위 실행 계획 (구현 세션용)

각 항목: **무엇 / 왜 / 파일 / 수용 기준(AC)**. P0는 "파이프라인을 믿을 수 있게" 만드는 최소집합.

### 구현 상태 (2026-07-06, claude/pipeline-hardening ~ -p2 반영)

| 계획 | 대상 | 상태 | 커밋 |
|---|---|---|---|
| P0-1 재스캔 덮어쓰기 차단 | A1/A4 | ✅ 완료 (V90 `classification_source`, 검토 잠금, 해시 통일) | 62a68eb |
| P0-2 RSS CDATA/RFC-1123 + 0건 가드 | A2 | ✅ 완료 | 62a68eb |
| P0-3 AI 초안 자동 승인 분리 | A3 | ✅ 완료 | 62a68eb |
| P0-4 holdout 평가 + 승급 게이트 | B1/B8 | ✅ 완료 (시간 분리 80/20, NDCG@4, holdout 없는 모델 activate 409). 단 '기존 모델 대비 우위' 수치 비교 게이트는 미구현 | 7685643 |
| P0-5 피처 단일 소스화 | B2/B3 | ✅ 완료 — as_*는 FEATURES 편입이 아니라 스냅샷 분리(`eventSnapshot.asContext`) 채택, rank_position 피처 제외 | 7685643 |
| P0-6 FALLBACK 정합화 | B4 | ✅ 완료 | 7685643 |
| P0-7 이벤트 입력 검증 | B5 | ✅ 완료 (sourceSurface 화이트리스트, 위조 400) | 7685643 |
| P0-8 스케줄러 풀/타임아웃/네이버 가시화/전체 갱신 | O1/O2/O3 | ✅ 완료 | 6d55466 |
| P0-9 데모 토큰 하드코딩 제거 | 보안 | ✅ 완료 — `@Profile` 대신 `agent.demo-activation-token` 프로퍼티(기본 빈값→거절) | 6d55466 |
| P1-1 AS 라벨 재확정 반영 + logSummaryId | B6/B7 | ✅ 완료 | ab868bb |
| P1-2 홈 스코어링 비동기화 + retention | B9 | ✅ 완료 (tri-state 서빙, 5분 스로틀, 30일 보존 스케줄러, 홈 0.49s→0.038s 실측) | a4d1e3b |
| P1-3 shadow 비교 대시보드 | B10 | ❌ 미착수 | — |
| P1-4 잡 실행 이력 + 관리자 노출 | O4 | ✅ 완료 (V91, `GET /api/admin/pipeline-job-runs`, AdminPriceJobsPage 패널) | ab868bb |
| P1-5 backfill / HTML 오탐 | A5/A6 | ✅ A5 완료, ❌ A6 미착수 | ab868bb |
| P1-6 데모 격리·freeze·스위퍼 | 보안/O6/O5 | ✅ `demo-feed-enabled` 게이트 + `DEMO_FREEZE_MUTATIONS`, ❌ O5 스위퍼 미착수 | a4d1e3b |
| P2 조건부 GET + poll_interval | A9 | ✅ 완료 | 9ad000a |
| P2 분산 잠금 | O7 | ✅ 완료 — ShedLock 대신 PG advisory lock | 9ad000a |
| P2 리랭커 관측성 | B11 | ✅ 완료 (`/health` 카운터, malformed 400) | 9ad000a |
| P2 canonical dedup·자동 retraining·온라인 A/B | — | ❌ 미착수 (정책 결정 필요) | — |
| (계획 외) 차단 자동 철수 | — | ✅ 추가 구현 (V92 `consecutive_failures`, 연속 403/429 3회 → 소스 자동 PAUSED·수동 재개) | 9ad000a |

### P0 — 정확성·데이터무결성·침묵실패 (선행)

**P0-1. 자산 재스캔 무음 덮어쓰기 차단 [A1/A4]** — ✅ 구현됨(62a68eb)
- 파일: `ManufacturerReleaseIntakeService.java`(upsertPost), `NaverShoppingOfferService`(withReleaseContext), V52 후속 마이그레이션
- 방향: (a) `classification_source` 컬럼(RULE/ADMIN/AI) 추가, 관리자/AI 확정 상태는 재스캔 덮어쓰기 제외. (b) `raw_payload`를 `||` 병합으로 변경. (c) draft/updatePost 해시 공식을 **단일 함수**로 통일.
- AC: 관리자가 IGNORED 처리한 게시물 → 소스 재스캔 후에도 IGNORED 유지 + aiAssetDraft/adminReview 보존(회귀 테스트).

**P0-2. RSS 파서 CDATA/RFC-1123 지원 + 파싱-0건 가드 [A2]** — ✅ 구현됨(62a68eb)
- 파일: `ManufacturerReleaseIntakeService.java`(cleanText, extractRssPosts, parsePublishedAt)
- 방향: 정규식 대신 XML 파서(또는 최소 CDATA 언랩 전처리) + `DateTimeFormatter.RFC_1123_DATE_TIME` 폴백. "본문은 변했는데 파싱 0건"이면 성공 처리 대신 WARN + last_content_hash 갱신 보류.
- AC: CDATA 감싼 실제 RSS 샘플에서 item 정상 추출, published_at 파싱 성공. 파싱 0건 시 소스 status가 성공으로 안 남음.

**P0-3. AI 초안 → 자산 연결 사이에 중간 상태 삽입 [A3]** — ✅ 구현됨(62a68eb)
- 파일: `ManufacturerReleaseIntakeService.java`, `NaverShoppingOfferService`(approveCatalogCandidateAsInactive), V52 후속
- 방향: `AI_DRAFTED`/`SPEC_DRAFT_PENDING_REVIEW` 상태 추가. AI 실행은 초안까지만, INACTIVE part 연결은 **관리자 명시 승인**으로 분리. `missingSpecFields` 존재 시 게시 불가 강제 표시.
- AC: AI 초안 실행 후 자동으로 part가 생기지 않음. 관리자 승인 클릭으로만 INACTIVE part 생성.

**P0-4. 훈련 평가를 holdout + 랭킹 지표로 교체 + 활성화 게이트 [B1/B8]** — ✅ 구현됨(7685643, '기존 모델 대비 우위' 수치 게이트는 미구현)
- 파일: `tools/reranker_service.py`, `recommendation_model_versions.metrics` 스키마, 관리자 모델 화면
- 방향: time-split(최근 N일 holdout) + NDCG@4/MRR/AUC + CTR-lift proxy 저장. self-MAE/RMSE만으로는 SHADOW 승급 후보 못 만들게. activate 전 "기존 모델 대비 shadow 지표 우위" 수치 게이트.
- AC: 학습 job metrics에 holdout NDCG@4 존재, 미달 모델은 activate API 거절.

**P0-5. 학습-서빙 피처 단일 소스화 [B2/B3]** — ✅ 구현됨(7685643, 아래 AC 주의)
- 파일: `RecommendationTrainingService.java`(featureSnapshot), `tools/reranker_service.py`(FEATURES), `recommendation_model_versions.feature_schema`
- 방향: 피처 목록·결측 기본값을 **한 곳**(feature_schema)에서 정의하고 학습·서빙이 동일 소비. price_age_days 결측 정책 통일(999 or 0 택1). as_* 20개를 FEATURES에 편입하거나 스냅샷에서 제거(dead feature 해소).
- AC: 동일 후보에 대해 훈련 시 피처 벡터와 서빙 시 피처 벡터가 일치(정합 테스트). as_* 피처가 학습에 실제 반영.
- *(구현 주의 — 2026-07-06)* 실제 구현은 AC의 **반대 선택지(제거안)** 채택: as_* 20개는 `features_snapshot`에서 분리해 `eventSnapshot.asContext`로만 보존하고 FEATURES에 편입하지 않았다(rank_position도 포지션 누수로 피처 제외). "as_* 피처가 학습에 실제 반영" AC는 폐기 — 지금 FEATURES에 as_*를 추가하면 학습-서빙 스큐를 재도입한다. 'AS 신호의 피처 편입'은 향후 과제.

**P0-6. FALLBACK 판정 정합화 [B4]** — ✅ 구현됨(7685643)
- 파일: `HomePartRecommendationService.java`(scoreCandidates)
- 방향: baseline-shadow/실패 판정 시 **Java deterministicScore 복원** 후 정렬. scoreSource=FALLBACK이면 실제로 Java 점수가 순위 결정.
- AC: 런타임 프로브에서 scoreSource=FALLBACK인 응답의 순위가 deterministicScore 순서와 일치.

**P0-7. 학습 이벤트 입력 검증 [B5]** — ✅ 구현됨(7685643)
- 파일: `RecommendationLearningService.java`(recordEvent)
- 방향: sourceSurface·eventType 화이트리스트 검증, 관리자 전용 surface(ADMIN_*, HOME_RECOMMENDED_PARTS)는 사용자 이벤트에서 거부. datasetItems included 기본값 재검토.
- AC: 사용자 토큰으로 ADMIN surface/과대 라벨 이벤트 주입 시 400.

**P0-8. 스케줄러 스레드풀 + fetch 타임아웃 + 네이버 실패 가시화 [O1/O2/O3]** — ✅ 구현됨(6d55466)
- 파일: `application.yml`(`spring.task.scheduling.pool.size`), `ManufacturerReleaseIntakeService`(RestClient 타임아웃), `NaverShoppingOfferService`(fetchOffers 오류 구분+백오프), `PriceJobWorker`(전체 카테고리 갱신)
- 방향: pool.size=4 + connect/read 타임아웃(10s/20s). 네이버 4xx/5xx/429를 errors로 구분 집계 + 지수 백오프 + 요청 간 지연. 워커가 카테고리 순회 버전 호출.
- AC: 한 소스 hang이 다른 잡을 안 막음. 네이버 429 시 로그/카운트에 error 노출. 관리자 가격 버튼이 전 카테고리 갱신.

**P0-9. demo-agent-activation-token 하드코딩 제거 [보안]** — ✅ 구현됨(6d55466, `@Profile("demo")` 대신 `agent.demo-activation-token` 프로퍼티 주입·기본 빈값이면 등록 거절)
- 파일: `PcAgentAsService.java:31`
- 방향: DB 발급·만료·1회성 activation token만 허용, build-time 데모 폴백 분리(`@Profile("demo")`).
- AC: 프로덕션 프로파일에서 하드코딩 토큰으로 등록 불가.

### P1 — 견고성·관측성

- **P1-1** AS 라벨 정정 → 이벤트 갱신 [B6], `log_summary_id` 명시 라벨링 [B7] — ✅ 구현됨(ab868bb)
- **P1-2** 홈 스코어링 비동기화/캐시 + shadow 테이블 샘플링·retention [B9] — ✅ 구현됨(a4d1e3b)
- **P1-3** shadow vs baseline 비교 지표 대시보드(fallback rate, 순위 역전율) [B10/O4] — ❌ 미착수(O4 잡 이력 노출은 P1-4로 해소)
- **P1-4** 스케줄 잡 이력 테이블(트리거·카운트·소요·성공여부) + AdminPriceJobsPage에 스케줄/릴리스 스캔 노출 [O4] — ✅ 구현됨(ab868bb)
- **P1-5** 후보 생성 실패 backfill 재시도 단계 [A5] — ✅ 구현됨(ab868bb), HTML 폴백 오탐 축소(정확 매칭) [A6] — ❌ 미착수
- **P1-6** 데모 컨트롤러 프로파일 격리 [보안] — ✅ 구현됨(a4d1e3b, `demo-feed-enabled` 프로퍼티 게이트), 데모 freeze 플래그 [O6] — ✅ 구현됨(a4d1e3b, `DEMO_FREEZE_MUTATIONS`), price_jobs stale 스위퍼 [O5] — ❌ 미착수

### P2 — 확장·최적화

- 조건부 GET(ETag/304) + poll_interval 존중 [A9] — ✅ 구현됨(9ad000a), 분산 잠금(ShedLock) [O7] — ✅ 구현됨(9ad000a, ShedLock 대신 PG advisory lock), 리랭커 관측성(메트릭/로그) [B11] — ✅ 구현됨(9ad000a). canonical fingerprint dedup, 자동 retraining cadence, 온라인 A/B — ❌ 미착수(정책 결정 필요).

---

## 8. 하지 말아야 할 것 (현재 안전선 유지)

- 홈 4-card 외 추천 본체(Build Chat/견적 순서)를 XGBoost로 직접 재정렬 — 작은 데이터·약한 라벨이 사용자 신뢰 훼손.
- AS 접수만으로 자동 negative 라벨 생성 — training link 필수 유지.
- raw gzip/전체 JSONL을 학습 피처로 투입 — AI_DIAGNOSIS_CONTRACT 위반.
- shadow 평가 없이 자동 ACTIVE 전환.
- AI가 AS 티켓 확정 필드 직접 수정.
- 자산 인테이크를 커뮤니티/리뷰 사이트로 확장.

---

## 9. 검증 상태 요약

| | 개수 |
|---|---|
| 감사 발견 | HIGH 19 / MEDIUM 36 / LOW 19 |
| 적대적 검증 | 11 CONFIRMED, 1 PARTIAL, **0 REFUTED**, 7 미검증(세션 한도) |
| 자체 재검증(운영 3건) | O1/O2/O3 코드 확인 완료 |
| GPT 보고서 대조 | 정확 8건, 오판 1건(compose scorer), 정정 2건 |

미검증 7건(A7·A8·B 일부·다나와 커버리지 등)은 반박된 것이 하나도 없는 감사 특성상 참으로 추정되나, 구현 착수 전 개별 재확인 권장.

> (2026-07-06) 확정 결함의 구현 반영 현황은 §7 '구현 상태' 표 참조. 미검증분 중 A7·A8은 여전히 미착수로 유효하다.
