# Sprint 1 시작 체크리스트

이 문서는 각 담당자가 첫 PR을 만들기 전에 확인할 작업 목록입니다. 목표는 완성 구현이 아니라 자기 담당 영역을 실제 API/state/service로 연결하기 시작하는 것입니다.

## 공통 체크

- `docker compose up --build`가 실행되는지 확인합니다.
- 자기 담당 경로를 [ROUTE_OWNERSHIP.md](ROUTE_OWNERSHIP.md)에서 확인합니다.
- API 요청/응답을 바꾸면 [API_CONTRACT.md](API_CONTRACT.md)와 [openapi.yaml](openapi.yaml)을 같은 PR에서 수정합니다.
- DB table/column/enum/status를 바꾸면 [DB_SCHEMA.md](DB_SCHEMA.md)를 같은 PR에서 수정합니다.
- mock 데이터는 담당 feature의 `mocks` 안에 추가합니다.
- 팀 공통 seed 데이터는 Flyway migration에 추가합니다. `*Seed.java`는 DB 연결 전 임시 응답이나 단위 테스트용으로만 사용합니다.
- PR 전에 `npm run test`, `python tools/validate_openapi.py`, `docker compose config`를 실행합니다.

## 1번: 견적/인증

첫 PR 목표:

- `apps/web/src/features/auth`의 로그인/회원가입 form 값을 실제 state와 연결합니다.
- Auth/User API skeleton을 계약 기준으로 정리하고 `POST /api/users`, `POST /api/auth/login`, `GET /api/auth/me`부터 테스트합니다.
- `apps/web/src/features/quote/pages`에서 요구사항 입력, 추천 결과, 부품 변경 흐름의 loading/error/success 상태를 추가합니다.
- `quoteApi.ts`를 통해 `/api/requirements/parse`, `/api/builds/recommend`, `/api/builds/{id}/change-part`를 호출합니다.

완료 기준:

- 로그인/회원가입 화면이 하드코딩 값만 쓰지 않습니다.
- Auth/User API가 `API_CONTRACT.md`와 `openapi.yaml`의 request/response/error 정책과 충돌하지 않습니다.
- 추천 생성 버튼이 API wrapper를 통해 동작합니다.
- route smoke test가 계속 통과합니다.

## 2번: 부품/가격/Tool

첫 PR 목표:

- `apps/web/src/features/parts`의 부품 표와 Tool check 흐름에 필요한 타입을 정리합니다.
- `apps/api/src/main/java/com/buildgraph/prototype/part`와 `price`에 DTO/service skeleton을 추가합니다.
- 5개 Tool API, `/api/parts`, `/api/price-alerts`의 요청/응답 필드를 구체화합니다.

완료 기준:

- 부품/가격/Tool 관련 데이터가 `common/MockData.java`에 추가되지 않습니다.
- Tool별 입력 규칙 초안이 코드나 OpenAPI에 드러납니다.
- 목표가 알림 흐름의 request/response가 프론트와 API에서 같은 이름을 씁니다.

## 3번: Agent/RAG

첫 PR 목표:

- `apps/api/src/main/java/com/buildgraph/prototype/agent`에 Agent 상태 전이 skeleton을 추가합니다.
- `apps/api/src/main/java/com/buildgraph/prototype/rag`에 RAG 근거 조회 경계를 정리합니다.
- 관리자 Agent/RAG/Tool 화면의 필드와 OpenAPI schema를 맞춥니다.

완료 기준:

- `QUEUED -> RUNNING -> RAG_SEARCHED -> TOOLS_CALLED -> SUMMARY_READY` 흐름이 코드에서 확인됩니다.
- 실패 시 `FAILED -> FALLBACK_READY` 경로를 확장할 위치가 명확합니다.
- Agent/RAG/Tool 관리자 화면이 같은 mock 파일에 섞이지 않습니다.

## 4번: PC Agent/AS

첫 PR 목표:

- `apps/pc-agent`의 JSONL 형식을 유지하면서 필요한 지표를 추가할 위치를 정합니다.
- `apps/web/src/features/support`의 AS 접수/티켓 상세 흐름을 실제 API wrapper와 연결합니다.
- `apps/api/src/main/java/com/buildgraph/prototype/log`와 `ticket`의 요청/응답 skeleton을 구체화합니다.

완료 기준:

- 최근 30분 로그 export와 업로드 동의 흐름이 화면/API에서 같은 필드를 씁니다.
- 원인 후보와 업그레이드 후보는 관리자 화면에만 노출됩니다.
- 사용자 티켓 상세와 관리자 티켓 상세의 정보 깊이가 구분됩니다.

## 5번: Infra/Admin/Auth Common

첫 PR 목표:

- `apps/web/src/features/admin`의 공통 shell, dashboard, route 진입점을 관리합니다.
- 인증 공통 token 전달과 관리자 route guard를 추가할 위치를 정합니다.
- CI, Docker Compose, k6 skeleton이 깨지지 않도록 유지합니다.

완료 기준:

- 관리자 공통 shell과 도메인별 admin page 소유권이 충돌하지 않습니다.
- 1번 Auth/User 구현과 `apps/web/src/lib/api.ts`, `RequireAdmin`, admin 401/403 정책이 충돌하지 않습니다.
- GitHub Actions가 web build/test, OpenAPI 검증, API build, API runtime smoke를 통과합니다.
- 인프라 변경 시 `docker compose config`가 통과합니다.
