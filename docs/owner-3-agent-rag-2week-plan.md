# 3번 Agent/RAG/Tool 담당 2주 계획

## 사전 확인 문서

개발 시작 전 아래 문서를 먼저 읽는다.

1. `docs/API_CONTRACT.md`
2. `docs/DB_SCHEMA.md`
3. `docs/ROUTE_OWNERSHIP.md`
4. `docs/openapi.yaml`
5. `docs/architecture.md`
6. `docs/sprint-1-start-checklist.md`

## 담당 화면과 기능 범위

| 구분 | 담당 범위 |
|---|---|
| 관리자 화면 | `/admin/agent-sessions/:id`, `/admin/tool-invocations/:id`, `/admin/rag-evidence/:id` |
| 백엔드 패키지 | `apps/api/src/main/java/com/buildgraph/prototype/agent`, `apps/api/src/main/java/com/buildgraph/prototype/rag` |
| DB 테이블 | `agent_sessions`, `tool_invocations`, `rag_evidence` |
| API | `POST /api/agent/sessions`, `POST /api/agent/sessions/{id}/run`, `GET /api/agent/sessions/{id}`, `GET /api/rag/search`, `GET /api/rag/evidence/{id}`, admin Agent/RAG/Tool 상세 API |
| 협업 지점 | 1번 추천 API, 2번 Tool 계산 결과, 4번 AS 분석 트리거, 5번 AdminShell/Auth |

3번의 핵심 책임은 추천이나 AS 결과 자체를 대신 만드는 것이 아니라, Agent 실행 과정에서 어떤 RAG 근거와 Tool 결과를 사용했는지 추적 가능하게 저장하고 관리자 화면에서 확인할 수 있게 만드는 것이다.

## 와이어프레임 기준 작업 화면

3번이 직접 구현 책임을 가지는 화면은 사용자 쇼핑몰 메인 화면이 아니라, 운영자가 Agent 판단 근거를 확인하는 관리자 상세 화면이다.

| 와이어프레임 화면 | route | 구현 파일 | 3번 작업 내용 | 연결 API |
|---|---|---|---|---|
| Agent/RAG/Tool 근거 상세 | `/admin/agent-sessions/:id` | `apps/web/src/features/admin/pages/AgentSessionAdminPage.tsx` | Agent 상태 전이, 실행 목적, summary, Tool 호출 목록, RAG 근거 목록을 실제 API 데이터로 표시 | `GET /api/admin/agent-sessions/{id}` |
| Tool Invocation 상세 | `/admin/tool-invocations/:id` | `apps/web/src/features/admin/pages/ToolInvocationAdminPage.tsx` | Tool 이름, status, confidence, latency, requestPayload, resultPayload, summary를 표시 | `GET /api/admin/tool-invocations/{id}` |
| RAG Evidence 상세 | `/admin/rag-evidence/:id` | `apps/web/src/features/admin/pages/RagEvidenceAdminPage.tsx` | sourceId, score, summary, chunkText, metadata, agentSessionId를 표시 | `GET /api/admin/rag-evidence/{id}` |

3번이 직접 만들지는 않지만 협업해야 하는 와이어프레임 지점은 아래와 같다.

| 협업 화면 | 주 담당 | 3번이 제공할 것 |
|---|---|---|
| AI 견적 입력 / 추가 질문 | 1번 | Agent session 생성과 실행 API 계약, 진행 상태 조회 방식 |
| 추천 Build 결과 | 1번 | `evidenceIds`, `toolInvocationIds`, RAG 근거 상세 링크/조회 API |
| 부품 변경 비교 | 1번/2번 | 변경 전후 설명에 사용할 RAG/Tool trace 저장 방식 |
| AS 접수 / 로그 업로드 | 4번 | `asTicketId` 기반 `AS_ANALYZE` Agent session 생성 방식 |
| AS 티켓 상세 / 관리자 AS 티켓 | 4번 | 원인 후보와 업그레이드 후보가 참조할 `rag_evidence` id 제공 방식 |

이번 2주 안에 3번 화면은 완성형 UI가 아니라, 와이어프레임에서 약속한 정보 구조가 실제 API 데이터로 채워지는 수준을 목표로 한다. 색상, 여백, 세부 시각 개선은 AdminShell과 공통 컴포넌트 기준을 따른다.

## 1주차 계획

| 일자 | 목표 | 산출물 | 검증 |
|---|---|---|---|
| 1일차 | 계약 문서와 현재 코드 대조 | Agent/RAG/Tool API, DB, 화면 담당 범위 정리 | `docs/API_CONTRACT.md`, `docs/DB_SCHEMA.md`, `docs/ROUTE_OWNERSHIP.md` 기준 충돌 확인 |
| 2일차 | Agent session 기본 흐름 정리 | 세션 생성, root 구분, 목적 타입 정리 | `POST /api/agent/sessions`, `GET /api/agent/sessions/{id}` |
| 3일차 | Agent 상태 전이 공통화 | `QUEUED -> RUNNING -> RAG_SEARCHED -> TOOLS_CALLED -> SUMMARY_READY -> SUCCEEDED` 전이 메서드 | 금지 전이 409, 중복 실행 409 |
| 4일차 | RAG 근거 기록/조회 연결 | 세션별 `rag_evidence` 저장, public/admin 조회 분리 | `GET /api/rag/evidence/{id}`, `GET /api/admin/rag-evidence/{id}` |
| 5일차 | Tool 호출 기록/조회 연결 | 세션별 `tool_invocations` 저장, request/result payload 보존 | `GET /api/admin/tool-invocations/{id}` |

## 2주차 계획

| 일자 | 목표 | 산출물 | 검증 |
|---|---|---|---|
| 6일차 | 목적별 mock Agent runner | `BUILD_RECOMMEND`, `BUILD_EXPLAIN`, `AS_ANALYZE`별 deterministic 실행 흐름 | 세션별 evidence/tool id 생성 확인 |
| 7일차 | 관리자 Agent 상세 화면 연결 | Agent timeline, Tool 목록, evidence 목록 API 연결 | `/admin/agent-sessions/:id` 화면 렌더링 |
| 8일차 | Tool/RAG 상세 화면 연결 | Tool payload 상세, RAG chunk/metadata/score 상세 표시 | `/admin/tool-invocations/:id`, `/admin/rag-evidence/:id` |
| 9일차 | 1번/2번/4번 연동 인터페이스 정리 | 추천 API, Tool 결과, AS ticket 분석 트리거가 호출할 내부 service 사용법 정리 | 담당자별 호출 예시 공유 |
| 10일차 | PR 전 검증과 문서 보강 | OpenAPI/문서/테스트/커밋 정리 | `bootJar`, 프론트 빌드, route smoke, OpenAPI 검증 |

## 협업 확인 포인트

| 상대 담당 | 확인할 내용 |
|---|---|
| 1번 | `POST /api/builds/recommend` 내부에서 Agent trace service를 어떤 순서로 호출할지 |
| 2번 | Tool 결과 DTO의 `status`, `confidence`, `summary`, `requestPayload`, `resultPayload` shape |
| 4번 | AS ticket 생성 후 `AS_ANALYZE` Agent session을 언제 만들고 실행할지 |
| 5번 | 관리자 route guard, AdminShell, 권한 오류 처리, 공통 API client 사용 방식 |

## 이번 2주 목표

2주 종료 시점에는 실제 LLM 품질보다 다음을 우선 완료한다.

- Agent 실행 세션이 생성되고 상태가 추적된다.
- RAG 근거와 Tool 호출 이력이 세션에 연결되어 저장된다.
- 관리자 화면에서 Agent timeline, Tool payload, RAG 근거 chunk를 확인할 수 있다.
- 1번 추천 흐름과 4번 AS 흐름이 같은 Agent/RAG/Tool trace 구조를 사용할 수 있다.
