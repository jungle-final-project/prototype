# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 필수 규칙 (AGENTS.md 요약)

- API 계약(`docs/API_CONTRACT.md`), DB 스키마(`docs/DB_SCHEMA.md`), 담당자(`docs/ROUTE_OWNERSHIP.md`) 확인 후 구현한다.
- API 요청/응답 구조를 바꾸면 반드시 `docs/openapi.yaml`을 함께 수정한다.
- 기능 범위, API 계약, DB 컬럼, 상태값, 인증/권한 정책이 불명확하면 추정 구현하지 않고 질문한다.
- 새 구현은 담당 feature/domain 안에 둔다. 공통 파일에 임시 구현을 쌓지 않는다.

### 테스트
```bash
# 프론트엔드 E2E (앱 실행 상태에서)
cd apps/web && npm run test               # playwright (포트 5174, webServer 자동 실행)
cd apps/web && npm run test:e2e:mvp       # MVP 흐름 E2E (포트 5173)

# API 유닛/통합 테스트
cd apps/api && ./gradlew test
# 단일 테스트 클래스 실행
cd apps/api && ./gradlew test --tests "com.buildgraph.prototype.user.UserControllerTest"
```
## 아키텍처 개요

모노레포 구조. `apps/web`(React), `apps/api`(Spring Boot), `apps/pc-agent`(Python CLI), Docker 인프라가 하나의 저장소에 있다.

### 서비스 포트
| 서비스 | 포트 |
|---|---|
| web (dev) | 5173 |
| api | 8080 |
| postgres | 5432 |
| redis | 6379 |
| rabbitmq | 5672 / 15672 |
| mailpit | 1025 / 8025 |
| xgb-reranker | 8091 |


# Self Quote SlotBoard Rules

## Scope
- `/self-quote` is being migrated to a slot-board-only estimate relationship board.
- Implement P0~P5, but work phase-by-phase: P0, P1, P2, P3, P4, P5.
- Do not jump ahead without completing tests for the current phase.

## Hard constraints
- Do not change backend APIs.
- Do not change route paths.
- Do not change auth guards.
- Do not change DB schema or migrations.
- Do not introduce new dependencies unless explicitly approved.
- Do not modify Home, My Quotes, Build detail, or other `BuildDependencyGraph` consumers.
- `/self-quote` must not render the old node graph or old list/cart workspace.
- Preserve the old PC category / full parts list / quote cart JSX as `LegacySelfQuoteListSections`, but do not render it.

## Design direction
- Final UX is not a physical PC assembly simulator.
- Final UX is an abstract motherboard topology / estimate relationship board.
- Use local SVG assets for slot glyphs:
  - `/slot-board/backgrounds/topology-board-bg.svg`
  - `/slot-board/parts/cpu.svg`
  - `/slot-board/parts/motherboard.svg`
  - `/slot-board/parts/ram.svg`
  - `/slot-board/parts/gpu.svg`
  - `/slot-board/parts/ssd.svg`
  - `/slot-board/parts/storage.svg`
  - `/slot-board/parts/psu.svg`
  - `/slot-board/parts/case.svg`
  - `/slot-board/parts/cooler.svg`
- Do not draw complex CPU/RAM/GPU/SSD/PSU/case/cooler shapes with CSS.
- SVG files are glyphs only. Product name, category, price, selected ring, status badge, and empty state must be rendered by React/CSS.
- Do not use remote images or bitmap images inside the SlotBoard glyph layer.

## Slot policy
- 8 logical slots: CPU, motherboard, RAM, GPU, SSD, PSU, case, cooler.
- RAM visual mini slots are fixed at 4.
- SSD visual mini slots are fixed at 2.
- Do not imply RAM/SSD mini slots are real motherboard capacities.
- Empty slots show dashed outline and `+ 부품 선택`.

## Candidate panel
- Reuse existing `GET /api/parts`.
- Include `compatibilitySource=QUOTE_DRAFT_CURRENT`.
- Load candidates in 20 item pages.
- Show PASS/WARN only.
- Hide FAIL candidates and show only `안 맞는 후보는 숨김`.
- WARN candidates are selectable and keep `간섭 주의`.

## Graph validation
- P1+ may reuse `POST /api/build-graphs/resolve`.
- Graph API must not be a hard dependency for basic SlotBoard rendering.
- If graph API fails, fallback topology must still render.

## Verification
After relevant `/self-quote` changes, run:
- `cd apps/web && npm run test -- self-quote.spec.ts`
- `cd apps/web && npm run test -- home.spec.ts`
- `cd apps/web && npm run build`