# Build Chat 전체 시나리오 QA 보고서

- 생성 시각: 2026-07-11T21:35:43.786826+09:00
- 대상: `http://127.0.0.1:18082`
- 소스 커밋: `50efdf00caebbcd7ff7574782fc9077bf495a87a`
- 모델 profile: `BUILD_CHAT_54_MINI_FAST` (`gpt-5.4-mini`)
- 실행 단계: `gate` / cache 상태: `off`
- 동시 실행 workers: 1
- 시나리오 200건 / 실제 대화 턴 220건
- 시나리오 판정: PASS 200 / FAIL 0
- 대화 턴 판정: PASS 220 / FAIL 0
- 수용 기준 판정: PASS
- 지연: 평균 1.815초 / p95 5.523초 / 최대 7.488초 / 5초 초과 18건 (8.2%)
- 실행 시간: 401.240초 / 처리량: 0.548 turn/s / HTTP 상태: {'200': 220} / 재시도: 0건
- 견적초안 변경: 없음

## 그룹별 결과

| 그룹 | PASS | FAIL |
|---|---:|---:|
| BOARD_FOCUS | 30 | 0 |
| BUDGET_BUILD | 30 | 0 |
| CACHE_MINIMAL_PAIR | 15 | 0 |
| CLARIFICATION | 20 | 0 |
| DRAFT_PREVIEW | 30 | 0 |
| PART_RECOMMEND | 30 | 0 |
| ROBUSTNESS | 20 | 0 |
| SIMULATION | 25 | 0 |

## 핵심 지표

- nextActionRate: 100.0%
- previewRate: 83.3%
- simulationRate: 84.0%
- boardFocusRate: 100.0%
- boardFocusVetoPassRate: 100.0%
- clarificationRate: 100.0%

## 실패 유형

- 없음

## 게이트 결론

- 안전 게이트를 통과했습니다. full/stability/cache/live web 후속 검증을 진행할 수 있습니다.

## 실패 상세

- 없음
## 판정 메모

- 사용자 표시 언어 판정은 message, quickReplies, build/simulation 표시 필드만 본다. 내부 warning code는 제외한다.
- 변경 요청은 최신 계약의 draft-edit 미리보기 build를 우선 기대하며, 챗 API가 실제 quote draft를 수정하면 치명 실패다.
- raw 응답은 `.qa-results/`에만 저장되며 Git 커밋 대상이 아니다.
