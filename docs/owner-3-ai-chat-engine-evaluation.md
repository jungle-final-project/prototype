# 3번 AI Chat Engine 정량 평가 기준

이 문서는 쇼핑몰 챗봇 UI가 호출할 `AiChatEngine`을 수치로 평가하기 위한 기준이다. AS Chat profile benchmark와 별개로, 견적 추천/부품 추천/견적 변경/가격 알림 보조 엔진의 품질을 측정한다.

## 평가 대상

| 대상 | 범위 | 제외 |
| --- | --- | --- |
| `AiChatEngine.respond()` | 자연어 메시지 -> intent, actions, 추천 후보, 설명 생성 | UI 렌더링, 실제 장바구니 저장 |
| `AiChatEngine.analyzeQuoteRequirement()` | 자연어 요구사항 -> 구조화 context, RAG evidence, agent trace | build/build_items 저장, Tool 계산 고도화 |
| `BuildQueryService.parse()` 연동 | 기존 `/api/requirements/parse`가 AI 엔진 결과를 사용 | 추천 build 저장 로직 자체 |

## 핵심 원칙

- 평가는 "답변이 그럴듯한가"가 아니라 "기능 API가 실행 가능한 구조화 결과를 안정적으로 주는가"를 본다.
- AI 엔진은 `quote_drafts`, `quote_draft_items`, `parts`를 직접 수정하지 않아야 한다.
- 직접 Tool check 결과를 `tool_invocations`에 저장하지 않는다. Agent/recommend 흐름 내부 trace만 저장한다.
- 모든 id는 내부 DB id가 아니라 public id 문자열이어야 한다.
- UI가 리뉴얼되어도 이 평가 기준은 유지한다.

## 총점 기준

100점 만점으로 평가한다.

| 영역 | 배점 | 자동화 여부 | 측정 지표 |
| --- | ---: | --- | --- |
| Intent 분류 정확도 | 20 | 자동 | `intent_accuracy` |
| Action 실행 가능성 | 20 | 자동 | `action_type_accuracy`, `action_payload_valid_rate` |
| 추천 후보 품질 | 20 | 자동 + 수동 | `recommendation_count_pass`, `category_coverage`, `tool_ready_rate`, `budget_fit_rate` |
| 요구사항 분석/RAG 근거성 | 15 | 자동 + 수동 | `parsed_context_valid_rate`, `evidence_attached_rate`, `agent_trace_rate` |
| 소유권/계약 준수 | 10 | 자동 | `no_forbidden_write_rate`, `public_id_rate` |
| 응답 속도 | 10 | 자동 | `p50_latency_ms`, `p95_latency_ms` |
| 사용자 답변 품질 | 5 | 수동 | `readability_score`, `unsupported_claim_count` |

## 통과 기준

| 단계 | 기준 |
| --- | --- |
| PR 최소 통과 | 총점 80점 이상, 소유권/계약 준수 10점 만점 |
| 데모 통과 | 총점 85점 이상, Intent 정확도 90% 이상, p95 2초 이하 |
| 최종 발표 전 목표 | 총점 90점 이상, Action payload valid 95% 이상, unsupported claim 0건 |
| 즉시 수정 필요 | `quote_drafts` 직접 쓰기, 내부 DB id 노출, 잘못된 action payload, Tool trace 중복 저장 |

## 평가셋 구성

최소 60개 케이스를 고정 평가셋으로 둔다. 케이스는 `message`, `surface`, `selectedCategory`, `context`, `expectedIntent`, `expectedActions`, `requiredFields`, `manualReviewNotes`를 가진다.

| 그룹 | 케이스 수 | 예시 | 기대 |
| --- | ---: | --- | --- |
| 전체 PC 추천 | 15 | `200만원 QHD 게임용 PC 추천해줘` | `FULL_BUILD_RECOMMEND`, 추천 3개, `OPEN_SELF_QUOTE` |
| 부품 추천 | 12 | `RTX 5070 중에 뭐가 좋아?` | `PART_RECOMMEND`, GPU 후보, `ADD_PART_TO_DRAFT` |
| 견적 변경 | 10 | `이 견적에서 램 64기가로 바꿔줘` | `BUILD_MODIFY`, `REPLACE_DRAFT_PART` |
| 가격 알림 | 8 | `이 GPU 80만원 되면 알려줘` | `PRICE_ALERT_HELP`, `CREATE_PRICE_ALERT.targetPrice=800000` |
| 설명 요청 | 7 | `왜 이 조합을 추천했어?` | `EXPLAIN`, 근거 설명 |
| 추가 질문 | 8 | `추천해줘` | `ASK_FOLLOW_UP`, 부족 정보 질문 |

## 지표 정의

### Intent

```text
intent_accuracy = expectedIntent와 actualIntent가 일치한 케이스 수 / 전체 케이스 수
```

권장 기준:

- 90% 이상: 데모 가능
- 95% 이상: 안정
- 85% 미만: 자연어 분류 기준 재검토 필요

### Action

```text
action_type_accuracy = expectedActions가 actualActions에 포함된 케이스 수 / 전체 케이스 수
action_payload_valid_rate = action payload가 실행 API 계약을 만족한 action 수 / 전체 action 수
```

Action별 필수 payload:

| Action | 필수 필드 |
| --- | --- |
| `OPEN_SELF_QUOTE` | `route` |
| `ADD_PART_TO_DRAFT` | `partId`, `category`, `quantity` |
| `REPLACE_DRAFT_PART` | `category`, `quantity` |
| `ADD_BUILD_TO_DRAFT` | `items[].partId`, `items[].category`, `items[].quantity` |
| `CREATE_PRICE_ALERT` | `targetPrice` |
| `ASK_FOLLOW_UP` | `missing`, `message` |

### 추천 후보 품질

```text
recommendation_count_pass = 추천 개수 조건을 만족한 케이스 수 / 추천 요청 케이스 수
category_coverage = 필수 카테고리가 채워진 추천 수 / 전체 추천 수
tool_ready_rate = attributes.toolReady=true인 part 수 / 전체 추천 part 수
budget_fit_rate = 예산 요청에서 totalPrice가 허용 범위 안에 든 추천 수 / 예산 요청 추천 수
```

추천별 기대:

| Intent | 수량 기준 | 품질 기준 |
| --- | --- | --- |
| `FULL_BUILD_RECOMMEND` | build recommendation 3개 | CPU, MOTHERBOARD, RAM, GPU, STORAGE, PSU, CASE, COOLER 포함 |
| `PART_RECOMMEND` | part recommendation 1~3개 | 모두 요청 category, `toolReady=true`, 가격 존재 |
| `BUILD_MODIFY` | 변경 후보 1~3개 | 요청 category와 일치 |

예산 허용 범위:

- 명시 예산이 있는 추천: 기준 build는 예산의 110% 이내
- 고성능형 후보는 예산 초과 가능하나 `confidence` 또는 summary에서 초과 가능성을 설명해야 함
- 예산이 없는 추천: `budget_fit_rate` 산정에서 제외

### 요구사항 분석/RAG

```text
parsed_context_valid_rate = 필수 context 필드가 유효한 응답 수 / parse 케이스 수
evidence_attached_rate = evidenceIds가 1개 이상인 응답 수 / RAG 대상 케이스 수
agent_trace_rate = agentSessionId가 존재하는 응답 수 / RAG 대상 케이스 수
```

필수 context 필드:

- `usageTags`
- `budget`
- `resolution`
- `preferredVendors`
- `priority`
- `performanceTier`
- `budgetPolicy`
- `mustHave`
- `confidence`

### 소유권/계약 준수

```text
no_forbidden_write_rate = 금지된 DB write가 없는 케이스 수 / 전체 케이스 수
public_id_rate = 응답 id가 public id 형식인 id 수 / 전체 id 수
```

금지된 write:

- `quote_drafts`
- `quote_draft_items`
- `parts`
- 직접 Tool check 결과의 `tool_invocations` 저장

허용된 write:

- `agent_sessions`
- `rag_evidence`
- 요구사항 분석 흐름의 Agent 상태 전이

### 속도

```text
p50_latency_ms = 전체 응답시간 중앙값
p95_latency_ms = 전체 응답시간 95퍼센타일
```

권장 기준:

| 요청 유형 | p50 목표 | p95 목표 |
| --- | ---: | ---: |
| `respond()` 내부 엔진 | 300ms 이하 | 1000ms 이하 |
| `analyzeQuoteRequirement()` deterministic | 500ms 이하 | 1500ms 이하 |
| `analyzeQuoteRequirement()` LLM 사용 | 6000ms 이하 | 12000ms 이하 |

## 자동 평가 결과 양식

평가 보고서는 `docs/reports/ai-chat-engine-evaluation-YYYYMMDD.md`에 남긴다.

```md
# AI Chat Engine 평가 결과 YYYY-MM-DD

## 요약

| 항목 | 점수 |
| --- | ---: |
| 총점 | 00 / 100 |
| Intent | 00 / 20 |
| Action | 00 / 20 |
| 추천 후보 | 00 / 20 |
| RAG/분석 | 00 / 15 |
| 계약 준수 | 00 / 10 |
| 속도 | 00 / 10 |
| 답변 품질 | 00 / 5 |

## 핵심 수치

| 지표 | 값 |
| --- | ---: |
| intent_accuracy | 0.00 |
| action_payload_valid_rate | 0.00 |
| category_coverage | 0.00 |
| tool_ready_rate | 0.00 |
| evidence_attached_rate | 0.00 |
| no_forbidden_write_rate | 0.00 |
| p50_latency_ms | 0 |
| p95_latency_ms | 0 |

## 실패 케이스

| message | expected | actual | 원인 | 조치 |
| --- | --- | --- | --- | --- |
```

## 현재 1차 기준선

현재 구현은 정량 평가 자동화의 최소 단위 테스트만 갖춘 상태다.

| 검증 | 결과 |
| --- | --- |
| 60개 고정 케이스 정량 평가 | 95.0 / 100 |
| `DefaultAiChatEngineTest` | 임시 로컬 복사본 기준 통과 |
| `DefaultAiChatEngineEvaluationTest` | 임시 로컬 복사본 기준 통과 |
| `bootJar -x test` | 원본 작업 폴더 기준 통과 |
| OpenAPI 검증 | 통과 |
| 프론트 Playwright | 61 passed |

최신 평가 결과는 `docs/reports/ai-chat-engine-evaluation-20260630.md`를 기준으로 본다.

현재 부족한 부분:

- 60개 고정 평가셋은 생겼지만 실제 DB가 아니라 mocked parts rows 기준이다.
- `respond()` 전체 케이스는 JUnit으로 반복 실행하며, 별도 benchmark script는 아직 없다.
- 추천 후보의 `category_coverage`, `tool_ready_rate`는 자동 집계하지만 `budget_fit_rate`는 아직 실제 DB 기준으로 집계하지 않는다.
- 수동 답변 품질 평가 기준은 문서화만 되어 있고 실제 리뷰 기록은 없다.

## 다음 작업

1. `tools/ai_chat_engine_cases.json` 생성
2. `tools/evaluate_ai_chat_engine.py` 또는 JUnit parameterized test 추가
3. 평가 결과 markdown 자동 생성
4. `docs/reports/ai-chat-engine-evaluation-YYYYMMDD.md`에 첫 기준선 기록
5. UI팀이 실제 홈/셀프견적 챗봇을 연결한 뒤 end-to-end 케이스 추가
