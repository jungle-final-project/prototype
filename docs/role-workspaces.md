# 역할별 작업 공간

| 담당 | 작업 공간 | 주요 책임 |
| --- | --- | --- |
| 1 | `apps/web/src/features/quote`, `apps/web/src/features/auth` | 소비자 견적 흐름, 추천 UI, 로그인/회원가입 화면 |
| 2 | `apps/api/src/main/java/com/buildgraph/prototype/part`, `apps/api/src/main/java/com/buildgraph/prototype/price`, `apps/web/src/features/parts` | 부품 DB, 검증 도구, 규칙 기반 성능 예측, 목표가 알림 |
| 3 | `apps/api/src/main/java/com/buildgraph/prototype/agent`, `apps/api/src/main/java/com/buildgraph/prototype/rag` | LLM/RAG/에이전트 오케스트레이션 골격과 대체 응답 정책 |
| 4 | `apps/pc-agent`, `apps/api/src/main/java/com/buildgraph/prototype/log`, `apps/api/src/main/java/com/buildgraph/prototype/ticket`, `apps/web/src/features/support` | PC 에이전트, 로그 업로드, AS 티켓 |
| 5 | `infra`, `apps/api/src/main/java/com/buildgraph/prototype/user`, `apps/api/src/main/java/com/buildgraph/prototype/admin`, `apps/web/src/features/admin` | 인증, 관리자, Docker, 대기열/cache, 부하 테스트 환경 |

## 첫 PR 체크리스트

| 담당 | 첫 PR 목표 | 완료 기준 |
| --- | --- | --- |
| 1 | 견적/인증 모의 화면 데이터를 API 호출과 로컬 폼 상태로 교체 | 요구사항 파싱, 빌드 추천, 로그인/회원가입 흐름을 UI에서 테스트할 수 있음 |
| 2 | 기존 controller 뒤에 부품/가격 DTO와 service 골격 생성 | `/api/parts`, `/api/tools/{tool}/check`, `/api/price-alerts`, `/api/price-snapshots/collect`가 공유 `MockData`에 의존하지 않음 |
| 3 | 에이전트 세션 상태 모델과 RAG 근거 경계 구현 | `/api/agent/sessions`, `/api/agent/sessions/{id}/run`, `/api/rag/search`, 관리자 에이전트 상세 화면에서 상태 타임라인과 근거를 볼 수 있음 |
| 4 | PC 에이전트 JSONL export를 업로드 및 AS 티켓 생성 흐름에 연결 | AS 요청이 동의 여부, 최근 30분 구간, 관리자 전용 원인 후보와 함께 AgentLogUpload 및 AsTicket 기록을 생성함 |
| 5 | 인증 보호 로직, 관리자 보호 로직, PR CI, 인프라 기본 검증 스크립트 추가 | 사용자/관리자 라우트가 role을 구분하고, GitHub Actions가 build/test 기본 오류를 막으며, Docker 서비스가 재현 가능하고 k6 스크립트 골격이 존재함 |

## 시드 소유권

시드/모의 데이터는 최종 기획안의 모듈 경계를 따릅니다. `common/MockData.java`는 `map()`과 `now()`만 제공하는 작은 유틸리티로 유지합니다.

| 모듈 | 시드 파일 | 담당 |
| --- | --- | --- |
| build | `apps/api/src/main/java/com/buildgraph/prototype/build/BuildSeed.java` | 1 |
| part/tool | `apps/api/src/main/java/com/buildgraph/prototype/part/PartSeed.java`, `ToolSeed.java` | 2 |
| price | `apps/api/src/main/java/com/buildgraph/prototype/price/PriceSeed.java` | 2 |
| agent | `apps/api/src/main/java/com/buildgraph/prototype/agent/AgentSeed.java` | 3 |
| rag | `apps/api/src/main/java/com/buildgraph/prototype/rag/RagSeed.java` | 3 |
| log | `apps/api/src/main/java/com/buildgraph/prototype/log/LogSeed.java` | 4 |
| ticket | `apps/api/src/main/java/com/buildgraph/prototype/ticket/TicketSeed.java` | 4 |
| user | `apps/api/src/main/java/com/buildgraph/prototype/user/UserSeed.java` | 5 |
| admin | `apps/api/src/main/java/com/buildgraph/prototype/admin/AdminSeed.java` | 5 |

## 프론트엔드 소유권

프론트엔드 파일은 기능 작업이 하나의 큰 페이지 파일에 몰리지 않도록 구현 담당자 기준으로 나누었습니다.

| 담당 | 프론트엔드 영역 | 비고 |
| --- | --- | --- |
| 1 | `apps/web/src/features/quote/pages`, `apps/web/src/features/quote/components`, `apps/web/src/features/quote/quoteApi.ts`, `apps/web/src/features/auth` | 요구사항 입력, 빌드 결과, 부품 변경 흐름, 견적 이력, 인증 UI |
| 2 | `apps/web/src/features/parts/pages`, `apps/web/src/features/parts/mocks`, `apps/web/src/features/parts/partsApi.ts` | 셀프 견적, 부품 표, 도구 검사, 목표가 알림 API 경계 |
| 3 | `apps/web/src/features/admin/pages/AgentSessionAdminPage.tsx`, `ToolInvocationAdminPage.tsx`, `RagEvidenceAdminPage.tsx`, `apps/web/src/features/admin/mocks/adminMock.ts` | 에이전트/RAG/도구 근거 검토 화면 |
| 4 | `apps/web/src/features/support`, `apps/web/src/features/support/supportApi.ts` | AS 접수, 티켓 상세, 로그 업로드 정책 |
| 5 | `apps/web/src/components/layout`, `apps/web/src/components/display`, `apps/web/src/components/feedback`, `apps/web/src/features/admin/pages/AdminDashboardPage.tsx` | 공통 shell, 공통 UI, 관리자 대시보드 |

## CI 소유권

저장소에는 최소 GitHub Actions 워크플로인 `.github/workflows/ci.yml`이 포함되어 있습니다.

| 검사 | 담당 | 목적 |
| --- | --- | --- |
| `apps/web`에서 `npm ci`, `npm run build`, `npm run test` | 5번이 유지보수하고 모든 담당자가 통과 상태를 유지 | 라우트 오류, TypeScript 오류, 프론트엔드 빌드 실패 탐지 |
| Java 21 환경의 `apps/api`에서 `./gradlew bootJar --no-daemon` | 5번이 유지보수하고 백엔드 담당자가 통과 상태를 유지 | 백엔드 컴파일 및 패키징 실패 탐지 |
| `docker compose config` | 5 | 병합 전 잘못된 compose 변경 탐지 |

CI는 의도적으로 배포, 브랜치 보호 설정, 전체 부하 테스트를 수행하지 않습니다. 이 항목은 이후 5번 담당자의 인프라 결정 범위로 남깁니다.

## 공통 계약 규칙

- 기존 API 응답 구조를 변경할 때는 같은 PR에서 `docs/openapi.yaml`을 함께 수정합니다.
- 운영급 오류 코드 전체 목록을 선행 확정하지는 않습니다. 성공 응답 구조와 인증 필요 여부처럼 담당자 간 구현 해석이 갈릴 수 있는 계약만 우선 고정합니다.
- 기능에 시드 데이터가 필요하면 담당 모듈 시드 파일에 추가합니다. 도메인 데이터를 `common/MockData.java`에 넣지 않습니다.
- 프론트엔드 기능에 모의 데이터가 필요하면 담당 기능의 `mocks` 디렉터리에 둡니다. `src/data/prototypeData.ts`는 호환용 모음 파일로만 유지합니다.
- 기능 API 호출은 페이지 컴포넌트에서 `api()`를 직접 호출하지 말고 담당 `*Api.ts` 파일에 추가합니다.
- 관리자 상세 화면은 최종 UX가 아니라 구현 목표 화면입니다. 상태, 담당자, API, 근거 필드를 명확하게 유지합니다.
- MVP 제외 범위를 유지합니다. 결제, 배송, 자체 원격제어, 정확한 FPS 보장, 최저가 보장은 구현하지 않습니다.
