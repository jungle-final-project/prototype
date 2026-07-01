# 부품 벤치마크/적합도 점수 정책

이 문서는 `benchmark_summaries`에 들어가는 수동 seed 점수의 의미를 정의한다.

## 원칙

- 점수는 `0~100` normalized score이고 카테고리 내부 비교용이다.
- CPU/GPU/RAM/STORAGE는 공개 벤치마크 또는 공개 성능 자료와 저장된 부품 스펙을 같이 사용한다.
- CPU/GPU는 원점수(raw benchmark)를 `metadata.rawBenchmarks`에 별도 저장한다.
- MOTHERBOARD/PSU/CASE/COOLER는 성능 벤치마크가 아니라 공식 스펙 기반 적합도 점수다.
- 정확 FPS, 렌더링 시간, 개선율을 사용자에게 보장하지 않는다.
- 추천/Tool/AI는 이 점수를 성능 예측의 보조 근거로만 사용한다.

## 저장 형식

공용 seed key는 다음 형식을 사용한다.

```text
normalized-fit-v1:{part_public_id}
```

`metadata` 필수 필드:

| 필드 | 의미 |
|---|---|
| `category` | 부품 카테고리 |
| `workload` | 점수가 대표하는 작업 범위 |
| `sourceName` | 공개 벤치마크 또는 공식 스펙 출처명 |
| `sourceUrl` | 기준 출처 URL |
| `secondarySourceUrls` | 보조 출처 URL 배열 |
| `rawScore` | 점수 산출에 사용한 저장 스펙 값 |
| `rawBenchmarkCoverage` | CPU/GPU 원점수 seed 여부. 원점수가 있으면 `PUBLIC_RAW_BENCHMARK_SEEDED` |
| `rawBenchmarkReferenceModel` | 원점수를 대표하는 기준 모델명 |
| `rawBenchmarkFamily` | 원점수 benchmark 계열 |
| `rawBenchmarks` | 공개 원점수 배열. 예: CPU Mark, Single Thread Rating, 3DMark Steel Nomad Graphics Score |
| `rawBenchmarkSourceName` | 원점수 출처명 |
| `rawBenchmarkSourceUrl` | 원점수 기준 URL |
| `rawBenchmarkSourceCheckedAt` | 원점수 확인일 |
| `rawBenchmarkNotes` | 보드/패키지 변형에 대한 해석 주의사항 |
| `normalizedFormula` | 산식 설명 |
| `sourceCheckedAt` | 기준 확인일 |
| `metadataVersion` | metadata 버전 |
| `scoreScope` | `CATEGORY_LOCAL_ONLY` |
| `guaranteePolicy` | `NO_EXACT_FPS_OR_RENDER_TIME_GUARANTEE` |

## 카테고리별 기준

| 카테고리 | 기준 |
|---|---|
| CPU | 코어/스레드, 최신 아키텍처, X3D/제품 티어, 공개 CPU 벤치마크 정책 |
| GPU | RTX 50 시리즈 등급, VRAM, 메모리 세대, 공개 GPU 벤치마크 정책 |
| RAM | 용량, DDR5 속도, XMP/EXPO, 작업 적합도 |
| STORAGE | 순차 읽기/쓰기, PCIe 세대, 용량 |
| MOTHERBOARD | 소켓, 칩셋 등급, PCIe 세대, Wi-Fi, DDR5, 폼팩터 |
| PSU | 정격 출력, 효율 등급, ATX 3.x, 12V-2x6/PCIe 5.x, 모듈러 |
| CASE | GPU 장착 길이, CPU 쿨러 높이, airflow/mesh 계열, 라디에이터 여유 |
| COOLER | 수랭 라디에이터/공랭 타워 등급, 소켓 지원 범위 |

## 구현 위치

- seed: `apps/api/src/main/resources/db/migration/V36__part_benchmark_normalized_scores.sql`
- raw benchmark seed: `apps/api/src/main/resources/db/migration/V37__part_public_raw_benchmarks.sql`
- 조회 API: `GET /api/parts`, `GET /api/parts/{id}`의 `benchmarkSummary`
- Tool 사용: `POST /api/tools/performance/check`의 `details.cpuBenchmarkScore`, `details.gpuBenchmarkScore`

## 검증 쿼리

`tools/check_benchmark_seed_quality.sql`로 다음을 확인한다.

- 모든 ACTIVE 부품에 benchmark row가 1개 이상 있는지
- 최신 `normalized-fit-v1:*` row의 `metadata.sourceUrl`이 비어 있지 않은지
- `score`가 null이 아니고 `0~100` 범위인지
- ACTIVE CPU/GPU의 최신 `normalized-fit-v1:*` row에 `metadata.rawBenchmarks`가 있는지

## Raw benchmark seed

CPU 원점수는 PassMark CPU Benchmark의 모델별 public submitted average를 기준으로 한다.

- `PassMark CPU Mark`
- `PassMark Single Thread Rating`

GPU 원점수는 UL Solutions 3DMark hardware review의 GPU 클래스별 점수를 기준으로 한다.

- `3DMark Steel Nomad DX12 Graphics Score`

주의사항:

- CPU의 정품/벌크/트레이/번들 row는 같은 CPU 모델 원점수를 공유한다.
- GPU 제조사별 OC/쿨링/전력 제한 차이는 존재하므로, RTX 5090/5080 같은 GPU 클래스 기준 원점수로 저장한다.
- 원점수는 RAG/Tool 근거로 쓰되, 사용자에게 정확 FPS나 작업 시간 보장으로 표현하지 않는다.

## Game FPS seed

게임별 FPS는 부품 단독 benchmark가 아니라 CPU/GPU 조합과 게임/해상도/옵션이 결합된 참고값이다.

- 저장 위치: `game_fps_benchmarks`
- 1차 출처: HowManyFPS 공개 페이지, PC-Builds FPS Calculator/GPU page
- 수집 방식: 수동 확인 seed만 허용. 자동 크롤링/대량 추출 금지
- Tool 사용: `POST /api/tools/performance/check`의 `details.gameFpsEvidence[]`

주의사항:

- `avg_fps`와 `one_percent_low_fps`는 공개 출처 기준 참고값이다.
- 실제 FPS는 맵, 장면, 드라이버, OS, 게임 패치, 냉각, 전력 제한, 세부 옵션에 따라 달라진다.
- `metadata.hardwareScope`가 `EXACT_PUBLIC_SESSION`이면 공개 세션의 CPU/GPU가 내부 대표 부품과 직접 대응한다.
- `metadata.hardwareScope`가 `GPU_COMPARISON_WITH_SELECTED_CPU`이면 공개 비교 페이지의 CPU/GPU 클래스 기준 참고값이다.
- Tool 응답의 `details.gameFpsEvidenceStatus`가 `RESOLUTION_FALLBACK`, `GENERAL_GAME_REFERENCE`이면 AI는 FPS 수치를 확정 근거처럼 사용하지 않는다. FPS evidence가 없을 때의 내부 상태값은 사용자/API 표시용 details에 넣지 않는다.
- `gameFpsEvidence[].match.resolutionMatched=false` 또는 `evidenceExactness`가 `*_FALLBACK`이면 “같은 조건의 실측”이 아니라 “인접 공개 참고값”으로 설명한다.
- 부족한 seed target은 사용자/API 응답이 아니라 내부 운영용 `game_fps_coverage_gaps` view와 `tools/check_game_fps_seed_quality.sql`로만 확인한다.
- 새 FPS seed row는 최소 `sourceCapturedText`, `sourceAccessMethod`, `sourceCpuName`, `sourceGpuName`, `sourceResolutionText`, `sourcePresetText`, `driverVersion`, `gameVersion`, `upscaling`, `frameGeneration`, `guaranteePolicy`를 metadata에 포함한다. 출처가 제공하지 않는 값은 `UNKNOWN`으로 명시한다.
- PC-Builds처럼 평균/최소/최대 FPS만 제공하는 출처는 `one_percent_low_fps`를 채우지 않고 `metadata.sourceMinFps`, `metadata.sourceMaxFps`에만 저장한다.
- 사용자 표현은 “예상 범위/공개 근거 기준”으로 제한하고 정확 FPS 보장으로 말하지 않는다.
