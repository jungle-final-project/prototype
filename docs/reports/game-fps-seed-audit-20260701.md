# Game FPS Seed Audit - 2026-07-01

## 결론

`game_fps_benchmarks`는 내부 추천/AI 근거 DB로 사용할 수 있는 최소 coverage를 충족한다.

- 필수값 누락: 0건
- `one_percent_low_fps > avg_fps`: 0건
- 활성 중복 조합 row: 0건
- 게임/해상도 coverage gap: 0건
- ACTIVE GPU class coverage gap: 0건
- 감사 metadata 누락: 0건
- DB-managed coverage gap: 0건

중요: seed 누락 여부는 내부 운영용 view와 SQL 스크립트로만 관리한다. `POST /api/tools/performance/check` 응답에는 “근거 없음” 목록을 반환하지 않는다.

## 보강 내용

- HowManyFPS 기반 초기 row 6건 유지
- PC-Builds FPS Calculator 기반 조합별 row 추가
  - PUBG: RTX 5060, RTX 5060 Ti, RTX 5070, RTX 5070 Ti, RTX 5080, RTX 5090 계열 FHD/QHD/4K
  - Valorant, Overwatch 2, Lost Ark, Cyberpunk 2077: RTX 5080 계열 FHD/QHD/4K
- PC-Builds GPU page 기반 인기 게임 row 추가
  - RTX 5060, RTX 5070, RTX 5070 Ti의 Valorant/Cyberpunk 2077 FHD/QHD/4K
- `game_fps_coverage_status` view는 preset이 정확히 일치하지 않더라도 같은 게임/해상도/GPU class 검증 row가 있으면 coverage를 충족으로 계산한다.
- exact preset 여부는 `exact_preset_rows`로 별도 확인한다.

## 현재 coverage 요약

| 게임 | row 수 | avg_fps 범위 |
|---|---:|---:|
| Cyberpunk 2077 | 13 | 57.00 - 155.00 |
| Lost Ark | 4 | 101.00 - 314.00 |
| Overwatch 2 | 5 | 165.00 - 672.00 |
| PlayerUnknown's Battlegrounds | 19 | 74.00 - 362.00 |
| Valorant | 13 | 442.00 - 1017.00 |

## 사용 규칙

- `gameFpsEvidenceStatus=MATCHED`이고 `match.resolutionMatched=true`인 row만 사용자가 요청한 게임/해상도와 같은 근거로 설명한다.
- `match.resolutionMatched=false` 또는 `evidenceExactness`가 `*_FALLBACK`이면 인접 공개 참고값으로만 설명한다.
- PC-Builds 출처는 평균/최소/최대 FPS를 제공한다. `one_percent_low_fps`가 아니므로 DB의 `one_percent_low_fps`에는 넣지 않고 `metadata.sourceMinFps`, `metadata.sourceMaxFps`에 저장한다.
- 사용자 표현은 “공개 자료 기준 참고 범위”로 제한하고, 정확 FPS 보장으로 말하지 않는다.
- 내부 누락 검사는 `tools/check_game_fps_seed_quality.sql` 또는 `SELECT * FROM game_fps_coverage_gaps ORDER BY priority;`로 수행한다.
