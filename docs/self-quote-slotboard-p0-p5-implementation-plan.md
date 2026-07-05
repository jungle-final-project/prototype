# `/self-quote` 슬롯 보드 P0~P5 통합 구현 계획

## Summary

`/self-quote`를 기존 노드 그래프 + 목록/장바구니 3영역 화면에서 **추상 메인보드 토폴로지형 슬롯 보드** 화면으로 전환한다.

- 이번 작업 범위는 **P0~P5 전체 완료**다. P0에서 멈추지 않는다.
- 내부 구현 순서는 **P0 → P1 → P2 → P3 → P4 → P5**를 유지한다.
- 각 phase가 끝날 때마다 phase gate(아래)를 통과해야 다음 phase로 넘어간다.
- 백엔드 API, route path, auth guard, DB schema는 변경하지 않는다.
- 홈/내 견적함 등 다른 `BuildDependencyGraph` 사용처는 건드리지 않는다.

## Phase Gate (모든 phase 공통)

각 phase 종료 시 아래를 실행한다. **하나라도 실패하면 다음 phase로 넘어가지 말고 먼저 수정한다.**

```bash
cd apps/web && npm run test -- self-quote.spec.ts
cd apps/web && npm run test -- home.spec.ts
cd apps/web && npm run build
```

## 확정 정책

| 항목 | 정책 |
|---|---|
| AI 선택 조합 패널 | P0에서는 유지한다 (슬롯 보드 위에 배치) |
| `?category=GPU` 딥링크 | 해당 슬롯 선택 + 후보 패널 자동 오픈으로 처리 (홈 카테고리 링크 유지) |
| FAIL 후보 | 후보 패널에서 숨긴다. 개수는 세지 않고 `안 맞는 후보는 숨김` 문구만 표시 |
| 현재 견적의 FAIL 문제 | P2에서 숨기지 않고 문제 슬롯/관계선 강조로 표시한다 |
| FAIL 있는 견적의 구매 | **구매하기 비활성화** (P2에서 적용) |
| FAIL 있는 견적의 저장 | **내 견적함 저장은 허용** |
| RAM mini slot (4칸) | quantity 합산 기준으로 채우고 4칸 초과분은 `+N` 표시 |
| SSD/STORAGE mini slot (2칸) | item 개수 기준으로 채우고 2칸 초과분은 `+N` 표시 |
| WARN 후보 | 즉시 적용 가능, `간섭 주의` 배지 유지 |
| 비로그인 슬롯 클릭 | 기존 정책대로 `/login?redirect=...` 리다이렉트 |
| 미완성 견적 | 저장/구매 모두 허용하되 `미장착 슬롯 N개가 있습니다` 배너 표시 (FAIL 구매 차단은 P2부터) |

## graph API 정책

- P0에서 `resolveBuildGraph`를 완전히 삭제하지 않는다. **SlotBoard 기본 렌더링의 hard dependency만 제거**한다 (graph 응답이 없어도 슬롯 보드는 렌더링된다).
- P1부터 `POST /api/build-graphs/resolve`를 **optional validation/label source**로 재사용한다.
- graph API가 실패해도 기본 topology 관계선은 항상 렌더링된다 (fallback topology).

## SVG 자산 정책

- 사용자가 제공하는 **sample SVG asset pack**을 `apps/web/public/slot-board/` 아래에 넣고 사용한다.
  - `backgrounds/topology-board-bg.svg`
  - `parts/cpu.svg`, `parts/motherboard.svg`, `parts/ram.svg`, `parts/gpu.svg`, `parts/ssd.svg`, `parts/storage.svg`, `parts/psu.svg`, `parts/case.svg`, `parts/cooler.svg`
- 복잡한 부품 형태를 CSS로 직접 그리지 않는다. local SVG glyph만 사용한다.
- 상품명/가격/상태 배지/선택 ring/빈 슬롯 문구는 React/CSS에서 렌더링한다.
- 원격 이미지/bitmap은 SlotBoard glyph 레이어에서 사용하지 않는다.
- **현재 저장소에 asset pack이 없다.** P0 구현 시작 전에 제공받아 배치해야 한다.

## 코드베이스 감사 요약

재사용 가능한 기존 자산 (백엔드/계약 변경 불필요):

| 필요 기능 | 기존 자산 |
|---|---|
| 후보 목록 | `listParts()` → `GET /api/parts` (`category`, `q`, `page`, `size`, `sort`, `compatibilitySource=QUOTE_DRAFT_CURRENT`). `PartRow.compatibility`에 PASS/WARN/FAIL + summary 포함 |
| 슬롯 데이터 | `getCurrentQuoteDraft()` → `QuoteDraft.items[]` |
| 담기/교체 | `putQuoteDraftItem(partId, 1)` — 단일 부품 카테고리는 백엔드가 카테고리 단위 교체 처리 |
| 개별 제거/수량 | `deleteQuoteDraftItem()`, `patchQuoteDraftItem()` |
| AI 조합 적용 | `applyAiBuildToQuoteDraft()` → quote draft invalidate로 슬롯 자동 갱신 |
| 견적함 저장 | `saveBuildFromChat()` + `quoteDraftToRecommendedBuild()` (SelfQuotePage 내 기존 함수) |
| 구매하기 | `/checkout` Link |
| 비로그인 처리 | `hasToken` + `/login?redirect=...` 패턴 |
| 카테고리 8종 | `PartCategory` = CPU, MOTHERBOARD, RAM, GPU, STORAGE(라벨 "SSD"), PSU, CASE, COOLER |
| 관계선 검증 데이터 | `resolveBuildGraph()` → `POST /api/build-graphs/resolve` (P1부터 optional source) |

새로 만드는 것 (프론트 내부 컴포넌트만):

- `apps/web/src/features/parts/components/slot-board/SlotBoard.tsx`
- `apps/web/src/features/parts/components/slot-board/SlotCandidatePanel.tsx`
- `apps/web/src/features/parts/components/slot-board/SlotStatusBar.tsx`
- `apps/web/src/features/parts/components/slot-board/LegacySelfQuoteListSections.tsx` (기존 3영역 JSX 보존용, 렌더링 안 함)
- 후보 패널 무한스크롤: 기존 page state 방식 대신 `useInfiniteQuery` 사용 (TanStack Query v5)

기존 테스트 영향 (`self-quote.spec.ts` 28개, `home.spec.ts`):

- 구 UI 강결합(사이드바 필터, 호환성 컬럼/정렬, 페이지네이션 버튼, 플로팅 그래프 리사이즈, 수량 스텝퍼, 장바구니 가격 추이 등) → 슬롯 보드 기준 재작성 또는 삭제.
- 부분 수정(견적함 저장, 구매 CTA, 챗봇 자동 실행, AI 선택 조합 패널 6개, 홈 카테고리 링크 2개) → 셀렉터/기대 문구 교체.
- 영향 없음(checkout 3개, 상품 상세 3개) → 유지.
- `home.spec.ts`의 `/self-quote` 이동 후 `견적 장바구니` 헤딩 기대 4곳 → 슬롯 보드 기준으로 수정.

주의할 상호작용:

- FAIL 클라이언트 필터 + 20개 페이지: 한 페이지가 전부 FAIL이면 다음 페이지를 자동 fetch한다. 표시 개수가 20 미만이어도 정책상 허용.

---

## P0 — 슬롯 보드 전환

1. **SVG asset pack 배치**: 제공받은 pack을 `apps/web/public/slot-board/`에 배치.
   → verify: dev 서버에서 각 경로 200 응답.
2. **테스트 먼저 재작성**: `self-quote.spec.ts` 슬롯 보드 기준 재작성 + `home.spec.ts` 기대 수정.
   → verify: 신규 테스트 red 확인.
3. **Legacy 추출**: 3영역 JSX + 관련 훅 → `LegacySelfQuoteListSections.tsx` (렌더링 안 함).
   `SelfQuotePage`에서 graph 렌더링의 hard dependency 제거 (`resolveBuildGraph` 호출 코드는 P1 재사용 대비 보존).
   AI 선택 조합 패널은 슬롯 보드 위에 유지.
   → verify: tsc 통과, `/self-quote`에 구 UI 미노출.
4. **SlotBoard**: topology 배경 + 8슬롯. draft 기준 `카테고리 + 상품명 + 가격` 표시.
   빈 슬롯 점선 + `+ 부품 선택`. RAM 4칸(quantity 합산, 초과 `+N`) / SSD 2칸(item 개수, 초과 `+N`) mini slot.
   단일 부품 슬롯 hover/focus 제거 액션. 비로그인 클릭 시 로그인 리다이렉트.
   glyph는 SVG, 텍스트/ring/배지/빈 상태는 React/CSS.
   → verify: 빈 draft 8슬롯 / AI 조합 적용 후 8부품 장착 테스트 green.
5. **SlotCandidatePanel**: 슬롯 클릭 시 데스크톱 우측 split 패널 / 모바일 bottom sheet.
   X·ESC 닫기. 선택 슬롯 파란 ring + 패널 제목에 카테고리.
   `GET /api/parts` + `compatibilitySource=QUOTE_DRAFT_CURRENT`, `useInfiniteQuery` 20개 단위.
   PASS/WARN만 표시 + `안 맞는 후보는 숨김` 문구 (전체 FAIL 페이지는 자동 다음 fetch).
   기본 `price_asc`, 옵션 `price_desc`/`name`.
   후보 카드: 이미지, 상품명, 제조사, 가격, 상태 배지, 담기/교체 버튼. WARN 즉시 적용 + `간섭 주의` 유지.
   RAM/SSD: 패널 상단 현재 항목 목록 + 개별 제거/교체 + 후보 선택 시 교체 대상 선택.
   `?category=` 딥링크 진입 시 해당 슬롯 선택 + 패널 자동 오픈.
   → verify: 패널 열림/닫힘, API 파라미터, FAIL 숨김, 딥링크 테스트 green.
6. **SlotStatusBar**: 총액, `장착 N/8`(카테고리 distinct), 내 견적함 저장(`saveBuildFromChat` 재사용,
   성공 시 현재 화면 유지 + 성공 메시지 + `내 견적함 보기` 링크), 구매하기(`/checkout`),
   미장착 시 `미장착 슬롯 N개가 있습니다` 배너. 저장/구매 모두 허용 (FAIL 구매 차단은 P2).
   → verify: 상태바 테스트 green.
7. **Phase gate** + UI QA 캡처: 데스크톱 빈 슬롯 / AI 조합 적용 후 / 후보 패널 열림 / 모바일 세로 리스트 + bottom sheet.

## P1 — 관계선 라벨 (graph API optional 재사용)

1. 기본 topology 관계선을 정의한다 (예: CPU–메인보드 소켓, 메인보드–RAM 규격, GPU–파워 전력, GPU–케이스 길이, 쿨러–케이스 높이). graph API 없이도 항상 렌더링되는 fallback.
2. `POST /api/build-graphs/resolve`(`source: QUOTE_DRAFT_CURRENT`)를 optional source로 호출해
   edge label/summary/status를 관계선에 반영한다.
3. graph API 실패/로딩 중에는 fallback topology 관계선만 표시하고 화면은 정상 동작한다.
4. 테스트: 관계선 라벨 표시, graph API 실패 시 fallback 렌더링 검증.
5. Phase gate.

## P2 — 문제 표시 (WARN/FAIL 강조)

1. 현재 견적의 검증 결과에서 WARN=`간섭 주의`, FAIL=`안 맞음`을 **문제 슬롯 + 관계선 강조**로 표시한다 (숨기지 않는다).
2. FAIL이 있는 견적은 **구매하기 버튼 비활성화** + 사유 표시. **내 견적함 저장은 허용** 유지.
3. 테스트: WARN/FAIL 슬롯·관계선 강조, FAIL 시 구매 비활성화 + 저장 허용 검증.
4. Phase gate.

## P3 — 슬롯 상세 / 복수 부품 관리 고도화

1. 슬롯 상세 정보 노출 강화 (스펙 요약, 가격 추이 등 기존 데이터 재사용 범위 내).
2. RAM/SSD 복수 부품 관리 UX 고도화 (P0의 목록+개별 제거/교체 기반 개선).
3. 테스트: 슬롯 상세, 복수 부품 관리 흐름 검증.
4. Phase gate.

## P4 — 애니메이션

1. 부품 장착 flash, 슬롯 변경 FLIP 등 장착/교체 애니메이션. 신규 의존성 없이 CSS transition/Web Animations API 범위에서 구현.
2. `prefers-reduced-motion` 존중.
3. 테스트: 애니메이션이 조작 흐름(담기/교체/제거)을 깨지 않는지 검증.
4. Phase gate.

## P5 — 캡처 QA / 반응형 polish

1. 반응형 polish: 데스크톱/모바일 레이아웃 겹침·오버플로 정리.
2. 캡처 QA: 데스크톱 빈 슬롯 / AI 적용 후 / 후보 패널 / 문제 표시 상태 / 모바일 bottom sheet.
3. 최종 Phase gate + 전체 회귀 확인.

---

## Public APIs / Interfaces

- 백엔드 API, route path, auth guard, DB schema 변경 없음. `docs/openapi.yaml` 수정 불필요.
- 재사용: `GET /api/parts`, `GET/PUT/PATCH/DELETE /api/quote-drafts/current(/items/{partId})`,
  `PUT /api/quote-drafts/current/apply-ai-build`, `POST /api/builds/from-chat`,
  `POST /api/build-graphs/resolve`(P1+, optional).
- 신규는 프론트 내부 컴포넌트만: `SlotBoard`, `SlotCandidatePanel`, `SlotStatusBar`, `LegacySelfQuoteListSections`.

## Assumptions

- 이번 작업 목표는 P0~P5 전체 완료이며, 내부 순서는 P0→P1→P2→P3→P4→P5다.
- 각 phase gate 통과 전에는 다음 phase를 시작하지 않는다.
- 기존 목록형 셀프 견적 UI는 삭제하지 않고 legacy 컴포넌트로 보존하되 렌더링하지 않는다.
- 메인보드의 실제 RAM/SSD 슬롯 수는 현재 데이터로 알 수 없으므로 mini slot은 시각적 표현이다.
- sample SVG asset pack은 사용자가 제공하며, 저장소에 배치되기 전에는 P0 구현을 시작하지 않는다.
