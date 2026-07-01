# AI Chat Engine 평가 결과 2026-06-30

## 요약

`tools/ai_chat_engine_cases.json`의 62개 고정 케이스를 기준으로 `DefaultAiChatEngine.respond()`를 정량 평가했다. 평가는 UI나 실제 장바구니 저장이 아니라, 쇼핑몰 챗봇 엔진이 intent와 action payload를 안정적으로 반환하는지를 확인하는 단위 평가다.

최종 결과는 95.0 / 100점으로, 문서상 데모 통과 기준인 85점 이상을 넘었다.

## 점수표

| 항목 | 점수 |
| --- | ---: |
| 총점 | 95.0 / 100 |
| Intent 분류 | 20.0 / 20 |
| Action type | 10.0 / 10 |
| Action payload | 10.0 / 10 |
| 추천 후보 수 | 8.0 / 8 |
| 카테고리 커버리지 | 6.0 / 6 |
| Tool-ready 부품 비율 | 6.0 / 6 |
| 소유권/금지 write 없음 | 10.0 / 10 |
| RAG/분석 구조 보존 | 10.0 / 10 |
| 속도 | 10.0 / 10 |
| 사용자 답변 기본 품질 | 5.0 / 5 |

## 핵심 수치

| 지표 | 값 |
| --- | ---: |
| 평가 케이스 수 | 62 |
| `intent_accuracy` | 1.000 |
| `action_type_accuracy` | 1.000 |
| `action_payload_valid_rate` | 1.000 |
| `recommendation_count_pass` | 1.000 |
| `category_coverage` | 1.000 |
| `tool_ready_rate` | 1.000 |
| `no_forbidden_write_rate` | 1.000 |
| `p50_latency_ms` | 0 |
| `p95_latency_ms` | 3 |

## 실행 명령

검증용 로컬 복사본에서 실행했다. OneDrive 경로에서는 Gradle test worker가 classpath를 잘못 읽는 현상이 반복되어, 같은 소스를 `C:\Users\cedis\AppData\Local\Temp\hyunjin-ai-chat-engine-test`로 복사해 테스트했다.

```powershell
.\gradlew.bat test --tests com.buildgraph.prototype.agent.DefaultAiChatEngineEvaluationTest --no-daemon
.\gradlew.bat test --no-daemon
```

## 테스트 로그 핵심

```text
AI_CHAT_ENGINE_EVAL cases=62 totalScore=95.0 intent=1.000 actionType=1.000 actionPayload=1.000 recCount=1.000 categoryCoverage=1.000 toolReady=1.000 forbiddenWrite=1.000 p50Ms=0 p95Ms=3
```

전체 API 테스트도 통과했다.

```text
BUILD SUCCESSFUL
```

## 평가 중 발견한 문제와 수정

초기 평가에서는 총점 93.2점이었고 추천 후보 수 지표가 0.919로 기준에 미달했다.

발견된 문제:

| 문제 | 원인 | 수정 |
| --- | --- | --- |
| `업그레이드 여유 있는 본체 추천`을 견적 변경으로 오분류 | `업그레이드` 단어만 보고 `BUILD_MODIFY`로 분류 | `업그레이드 여유`, `추후 업그레이드`, `향후 업그레이드`는 전체 견적 요구로 해석 |
| 카테고리 없는 견적 변경에서 후보가 비어 있음 | `BUILD_MODIFY` 기본 category는 payload에만 `RAM`을 넣고 후보는 비움 | 기본 category를 `RAM`으로 통일하고 후보 3개 반환 |
| `쿨러가 9만원 되면 알려줘`를 가격 알림으로 처리하지 못함 | 만원 금액 정규식이 2자리 이상만 허용 | `9만원` 같은 1자리 만원 금액도 인식 |
| 카테고리 포함 변경 요청이 부품 추천으로 오분류 | category 분류가 modify 분류보다 먼저 실행 | 가격 알림 다음에 modify signal을 먼저 평가하도록 순서 조정 |
| `300만원 이상` 요청에서 200만원대 추천이 섞일 수 있음 | 일반 예산 요청과 하한 예산 요청을 구분하지 않고 0.78/0.96/1.14 배율을 사용 | `이상/최소/넘게` 표현은 하한 예산 요청으로 보고 1.35/1.50/1.70 배율 플랜 사용 |

## 한계

- 이 평가는 실제 DB가 아니라 mocked `parts` rows를 사용한다.
- LLM 호출 품질은 평가하지 않는다. 현재 `respond()`는 deterministic 엔진이고, `/api/requirements/parse`의 LLM/RAG 품질은 별도 평가가 필요하다.
- 추천 후보의 실제 성능 적합도는 2번 Tool 고도화와 실제 `parts.attributes` 품질에 의존한다.
- 속도 지표는 로컬 단위 테스트 기준이다. Docker API 왕복, DB 조회, LLM 호출 시간은 포함하지 않는다.

## 다음 평가 과제

1. 실제 seed DB를 붙인 통합 평가
2. `/api/requirements/parse`의 RAG evidence 부착률과 LLM parse 정확도 평가
3. 2번 Tool 결과가 붙은 추천 build 품질 평가
4. UI 챗봇 연결 후 E2E 평가
