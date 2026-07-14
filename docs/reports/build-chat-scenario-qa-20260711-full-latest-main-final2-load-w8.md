# Build Chat 전체 시나리오 QA 보고서

- 생성 시각: 2026-07-11T21:53:55.555757+09:00
- 대상: `http://127.0.0.1:18082`
- 소스 커밋: `50efdf00caebbcd7ff7574782fc9077bf495a87a`
- 모델 profile: `BUILD_CHAT_54_MINI_FAST` (`gpt-5.4-mini`)
- 실행 단계: `full` / cache 상태: `off`
- 동시 실행 workers: 8 (기능 검증용, latency 기준선으로 사용하지 않음)
- 시나리오 700건 / 실제 대화 턴 780건
- 시나리오 판정: PASS 699 / FAIL 1
- 대화 턴 판정: PASS 779 / FAIL 1
- 수용 기준 판정: FAIL
- 지연: 평균 2.285초 / p95 5.386초 / 최대 8.633초 / 5초 초과 71건 (9.1%)
- 실행 시간: 225.690초 / 처리량: 3.456 turn/s / HTTP 상태: {'200': 780} / 재시도: 0건
- 견적초안 변경: 없음

## 그룹별 결과

| 그룹 | PASS | FAIL |
|---|---:|---:|
| BOARD_FOCUS | 100 | 0 |
| BUDGET_BUILD | 100 | 0 |
| CACHE_MINIMAL_PAIR | 60 | 0 |
| CLARIFICATION | 80 | 0 |
| DRAFT_PREVIEW | 100 | 0 |
| PART_RECOMMEND | 100 | 0 |
| ROBUSTNESS | 70 | 0 |
| SIMULATION | 89 | 1 |

## 핵심 지표

- nextActionRate: 100.0%
- previewRate: 73.0%
- simulationRate: 70.0%
- boardFocusRate: 100.0%
- boardFocusVetoPassRate: 100.0%
- clarificationRate: 85.0%

## 실패 유형

- `CATEGORY_MISMATCH`: 1건

## 실패 상세

### simulation-case-079 / turn 1

- 그룹: `SIMULATION` / 지연: 4.941초 / HTTP 200
- 입력: 현재 견적의 케이스를 리안리 216 케이스(으)로 달면 현재보다 뭐가 좋아져?
- 실패: CATEGORY_MISMATCH
- 응답: 리안리 216로 바꾸면 보통 현재 4000D Airflow 대비
- 전면 쿨링 여유가 더 좋아질 수 있고
- 기본 팬 구성/흡기 효율이 더 낫다면 CPU·GPU 온도와 소음이 개선될 가능성이 있습니다.

다만 정확한 체감은 216의 정확한 팬 구성, 전면 구조, 상단/전면 라디 지원에 따라 달라집니다. 원하시면 현재 견적 기준으로 "호환성/쿨링/소음/크기"까지 비교해드릴게요.

## 판정 메모

- 사용자 표시 언어 판정은 message, quickReplies, build/simulation 표시 필드만 본다. 내부 warning code는 제외한다.
- 변경 요청은 최신 계약의 draft-edit 미리보기 build를 우선 기대하며, 챗 API가 실제 quote draft를 수정하면 치명 실패다.
- raw 응답은 `.qa-results/`에만 저장되며 Git 커밋 대상이 아니다.
