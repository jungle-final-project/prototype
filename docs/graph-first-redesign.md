# 챗봇 용도 축소 + 셀프견적 그래프 중심 개편 설계

작성일: 2026-07-04. 팀 회의(구두) 결정사항을 코드 설계로 구체화한 문서다. PR #52(`feat/ai-quote-assistant-ui`) 머지 이후의 `main`(430bc16)을 기준으로 한다.

## 0. 작업 원칙

- **1순위 속도, 2순위 품질.** 사용자 체감 latency(브라우저 기준)가 최우선 지표다. LLM 호출은 최후 수단이고, 고정 응답/deterministic/캐시 경로를 우선한다. 목표: fixed/route ≤1s, 시뮬레이션 ≤1.5s, deterministic 추천/캐시 ≤3s, LLM full ≤5s.

- **디자인/UI는 전적으로 UI 담당 팀원 소유다.** 이 작업에서 화면 디자인·레이아웃을 새로 만들지 않는다. 팀원이 이미 올린 구조(PR #52 사이드패널, 그래프 컴포넌트)에 기능을 흡수시키는 방식으로 진행한다.
- 기능 제거 시에도 남는 UI의 시각 스타일은 팀원 코드 그대로 유지한다.

## 1. 회의 결정사항 (확정)

1. **셀프 견적 화면 안에서** 그래프 노드(추천 관계도)가 메인으로 올라온다. 이 레이아웃 개편은 UI 담당 팀원이 진행한다. 홈 화면은 이번 범위가 아니다.
2. 챗봇은 크롬 "Gemini에게 물어보기"처럼 **본문이 옆으로 밀리는 전체 높이 사이드패널**로 동작한다. (PR #52에서 이미 구현됨: `.ai-assistant-open .screen-shell { margin-right: 420px }`)
3. 챗봇 기능은 아래로 **축소**한다 (우리 담당 범위).
   - 자연어 예산 견적 추천 ("200만원 게이밍 PC")
   - 그래프 노드(현재 견적 드래프트) 기반 견적 생성/완성
   - 내부 DB 자산(`part_benchmark_normalized_scores` 등) 기반 성능 차이 비교
4. 그래프 노드 클릭 시 단순 호환 부품 리스트 제공은 **그래프 UI가 담당**한다 (챗봇 아님). 이미 `/api/parts/compatible-candidates`로 구현되어 있다.
5. 챗봇에서 제거되는 기능의 백엔드 코드는 삭제하되, 삭제 전 상태를 `backup/build-chat-full-feature` 브랜치에 보존한다. (완료: origin에 push됨)

## 2. 챗봇 기능 명세 (축소 후)

### 남기는 기능

| 기능 | 입력 예 | 응답 | 백엔드 경로 |
| --- | --- | --- | --- |
| 예산 견적 추천 | "200만원 게이밍 PC 추천" | builds[] (2~3개 티어) + 검증 칩 | `BUILD_RECOMMEND` |
| 그래프 기반 견적 완성 | "지금 견적 기준으로 나머지 채워줘" | currentQuoteDraft를 제약으로 한 builds[] | `BUILD_RECOMMEND` + draft 컨텍스트 |
| 성능 차이 비교 | "CPU를 9700X로 바꾸면?" | simulation (score/FPS/spec 비교 카드) | `SIMULATE_REPLACEMENT` |
| 명확화 질문 | target 불명확한 비교 요청 | 되묻기 (임의 후보 선택 금지, actions=[]) | `ASK_CLARIFICATION` |

- 견적 결과의 "이 조합으로 셀프 견적 보기"(= `apply-ai-build`)는 **견적 생성의 출력 경로이므로 유지**한다.
- 시뮬레이션은 항상 `actions=[]`, 드래프트를 변경하지 않는다 (기존 원칙 유지).
- 챗봇은 현재 드래프트(그래프 상태)를 항상 컨텍스트로 전송한다 — 지금은 `surface === 'self-quote'`일 때만 보내는데, **모든 surface에서 전송**하도록 변경.

### 제거하는 기능

| 기능 | 프론트 제거 대상 | 백엔드 제거 대상 (intent) |
| --- | --- | --- |
| 화면 이동/네비게이션 | `fastRouteIntent`, `isAllowedUserRoute`, OPEN_ROUTE 액션 처리, 네비게이션 퀵프롬프트 | `NAVIGATE_STATIC`, `NAVIGATE_CATEGORY`, `NAVIGATE_PART_DETAIL`, `FILTER_PART_SEARCH` |
| 장바구니 조작 대화 | `DraftActionCards`, `autoExecuteActions`, `executeDraftAction`의 REMOVE/QUANTITY/PUT 분기, `isApplyLatestBuildIntent` | `MUTATE_DRAFT_REMOVE`, `MUTATE_DRAFT_QUANTITY`, `MUTATE_DRAFT_REPLACE_EXACT`, `MUTATE_DRAFT_RECOMMEND` |
| 단일 부품 카테고리 추천 | `PartRecommendationCards`, `appliedPartPreferences` 흐름 | `PART_RECOMMEND` |
| 일반 설명/상담 | — | `EXPLAIN_CURRENT` (LLM_FULL은 예산 견적·명확화의 fallback으로만 유지) |

- `AiDraftAction` 액션 처리에서 남는 것은 `ADD_BUILD_TO_DRAFT`(견적 적용)와 `ASK_FOLLOW_UP`(명확화)뿐이다.
- 제거되는 intent의 semantic cache 서명, 프롬프트 템플릿, 테스트도 함께 정리한다.
- `docs/openapi.yaml`의 `/api/ai/build-chat` 요청/응답 계약을 함께 갱신한다.
- 퀵프롬프트는 축소된 3가지 용도(예산 견적 / 견적 완성 / 성능 비교)로 교체한다. 스타일은 기존 팀원 구현 그대로.

### 그래프 → 챗봇 진입점 (신규, UI 팀원과 협의)

1. 그래프 노드 후보 패널에 "AI에게 성능 비교 물어보기" → 챗봇 열림 + `"{현재 부품}을(를) {후보 부품}(으)로 바꾸면?"` 프롬프트 프리필.
2. "AI로 견적 완성" → 챗봇 열림 + 드래프트 컨텍스트로 견적 완성 요청.
3. 구현: 기존 이벤트 버스 `AI_BUILD_ASSISTANT_OPEN_EVENT`를 `CustomEvent<{ prefill?: string; autoSubmit?: boolean }>`로 확장. **버튼 배치/디자인은 UI 팀원 몫** — 우리는 이벤트 계약과 수신 로직만 제공한다.

## 3. 변경 파일 설계

### 프론트엔드 (기능만, 디자인 불변)

| 파일 | 변경 |
| --- | --- |
| `features/quote/components/AiBuildAssistant.tsx` | fastRoute/draft mutation/part recommendation 로직 제거, 퀵프롬프트 3종 교체, 모든 surface에서 draft 컨텍스트 전송, prefill 이벤트 수신. 패널 레이아웃/스타일 불변 |
| `features/quote/aiSelection.ts` | `appliedPartPreferences` 등 제거 기능 타입 정리, 기존 세션 필드는 무시(마이그레이션) |
| `lib/events.ts` | OPEN 이벤트 payload 확장 |
| `App.tsx` | draft 컨텍스트 상시 전송에 맞춰 surface 로직 단순화 |
| `apps/web/tests/*` | 제거된 챗봇 기능 시나리오 삭제/재작성 |

### 백엔드

| 파일 | 변경 |
| --- | --- |
| `build/BuildChatIntent.java` | enum 축소: `SIMULATE_REPLACEMENT`, `BUILD_RECOMMEND`, `ASK_CLARIFICATION`, `LLM_FULL`만 유지 |
| `build/BuildChatIntentRouter.java` | 네비게이션/필터/뮤테이션/부품추천 분기 제거 |
| `build/BuildChatService.java` | 제거된 intent 처리 경로 삭제, draft 컨텍스트 기반 견적 완성 유지/보강 |
| cache 서비스 | 제거된 intent 서명 정리 |
| `docs/openapi.yaml` | `/api/ai/build-chat` 계약 축소 반영 |
| 백엔드 테스트 | 제거 intent 테스트 삭제, minimal pair 테스트를 축소 범위로 재정의 ("바꾸면"=simulation vs "추천"=build, 불명확 target=ASK_CLARIFICATION) |

- `/api/quote-drafts/*`, `/api/build-graphs/resolve`, `/api/parts/compatible-candidates`는 **변경 없음** (그래프 UI가 사용).
- 홈(`HomePage.tsx`), 셀프견적(`SelfQuotePage.tsx`) 레이아웃은 **건드리지 않는다** (UI 팀원 영역).

## 4. 구현 순서

1. **PR A — 챗봇 기능 축소 (프론트 로직+백엔드+계약)**: 이번 작업. 디자인 불변.
2. **셀프견적 그래프 메인 승격**: UI 팀원 진행. 우리는 이벤트 계약(prefill)과 API를 미리 제공.
3. **PR C — 그래프→챗봇 연결**: 팀원 UI가 올라온 뒤 진입점 버튼을 팀원 디자인에 맞춰 연결.

각 PR 검증 루틴: `python tools/validate_openapi.py docs/openapi.yaml` → `npm --prefix apps/web run test && build` → `gradlew test` → `docker compose up --build -d` → health 확인.

## 5. 리스크 / 미확정

- `/requirements/new`(자연어 요구사항 → 추천 플로우)와 챗봇 예산 추천의 중복은 이번 범위 밖, 팀 확인 필요.
- 담당 영역(ROUTE_OWNERSHIP) 상 intent router는 3번, 챗봇 화면은 1번 소유 — 제거 PR은 해당 담당자 리뷰 필요.
- 세션 스토리지에 남은 기존 대화(제거된 기능의 액션 카드 포함)는 렌더링에서 무시되도록 방어한다.
