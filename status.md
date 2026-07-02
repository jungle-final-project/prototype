# 2026-07-02 PCagent main Agent AS runtime QA

## Current goal

- `pcagent/main` 최신 커밋 `961d0ba` 기준으로 서버, 웹, PC Agent register/consent/heartbeat/upload/supportDecision happy path를 실제 런타임에서 검증한다.

## Done

- `qa/pcagent-main-runtime-qa` 브랜치를 `pcagent/main` 최신 커밋에서 생성했다.
- 문서 기준은 `docs/agent-as/E2E_HAPPY_PATH.md`, `docs/agent-as/README.md`, `apps/pc-agent/README.md`를 확인했다.
- 기존 Compose DB volume은 Flyway V53/V54 checksum mismatch가 있어 건드리지 않고, 별도 QA 컨테이너를 사용했다.
- QA API: `http://127.0.0.1:18080`, QA Web: `http://127.0.0.1:15173`, QA DB: `pcagent-postgres-runtime-qa`로 격리 실행했다.
- register 전 `agent status`는 `UNREGISTERED`, register 후 `REGISTERED`를 확인했다.
- `SERVER_UPLOAD` consent accepted, heartbeat `ACTIVE`, gzip upload, `ticketId` 반환을 확인했다.
- 관리자 `PATCH /api/admin/as-tickets/{ticketId}`로 `supportDecision=REMOTE_POSSIBLE`, `reviewStatus=APPROVED` 저장을 확인했다.
- 사용자 `GET /api/as-tickets/{ticketId}`와 웹 `/support/{ticketId}` 화면에서 `RULE_READY`, `APPROVED`, `REMOTE_POSSIBLE` 반영을 확인했다.
- 같은 upload `Idempotency-Key` 재시도 시 같은 `ticketId`가 반환되는 것을 확인했다.
- 화면 증거: `artifacts/qa/pcagent-support-decision.png`.

## Fixed

- multipart upload 요청을 generic Agent idempotency filter가 body caching으로 소비해 `file` part가 사라지는 문제를 수정했다.
- `/api/agent/log-uploads`는 service 레벨에서 `agent_upload_jobs(device_id,idempotency_key)` 기준 replay/conflict를 처리하도록 수정했다.
- upload SQL에서 `Instant`와 `delete_after` timestamp 파라미터 타입 추론 오류를 수정했다.
- PC Agent gzip 생성이 재시도마다 다른 gzip bytes를 만들지 않도록 deterministic gzip `mtime=0`을 적용했다.
- missing `Idempotency-Key` header도 공통 `VALIDATION_ERROR` 응답으로 처리되도록 보강했다.

## Remaining issues

- 기존 Compose DB volume은 V53/V54 checksum mismatch 상태다. 이번 QA에서는 데이터 삭제를 하지 않고 별도 QA DB로 우회했다.
- `apps/pc-agent`의 `supportUrl` 생성은 `apiBaseUrl`이 기본 `:8080`일 때만 웹 `:5173`으로 바꾼다. 이번 격리 QA처럼 `:18080`을 쓰면 출력 URL은 API 포트 기준이라 실제 웹 URL은 `http://127.0.0.1:15173/support/{ticketId}`를 사용해야 했다.

## Last verification

- `.\gradlew.bat test --tests com.buildgraph.prototype.config.security.PcAgentControllerSecurityTest --tests com.buildgraph.prototype.agent.PcAgentAsServiceTest --no-daemon` 성공. 한글 경로 ClassNotFound 기존 이슈를 피해 `C:\codex\pcagent-prototype` junction에서 실행했다.
- `docker build -t prototype-api:latest .\apps\api` 성공.
- `docker build -t prototype-web:latest .\apps\web` 성공.
- `python tools\validate_openapi.py` 성공. 결과: `OpenAPI validation passed: 63 paths`.
- `docker compose config --quiet` 성공.
- 최종 QA ticketId: `9e39f4bd-440a-439d-b690-6457ec3e0354`.

# Agent AS Goal 4/5 status

Updated: 2026-07-02

## Member A scope

- Goal 4: Agent Register + Consent hardening
- Goal 5: Agent Heartbeat hardening
- This note records policy points that remain ambiguous in the project docs. No new feature behavior is introduced here.

## Confirmed current behavior

- `POST /api/agent/devices/register` is the only `/api/agent/**` endpoint allowed before Agent token authentication.
- `POST /api/agent/consents`, `POST /api/agent/heartbeat`, and `POST /api/agent/log-uploads` require an Agent bearer token.
- Agent mutation APIs require `Idempotency-Key`, including heartbeat under the current implementation.
- Register returns the raw agent token only in the response and stores only `agent_token_hash`.
- Log upload checks accepted `SERVER_UPLOAD` consent before creating upload/ticket rows.

## Need confirmation

- Register duplicate policy is not fully settled by the contract docs.
- Current MVP behavior refreshes the existing device token when the same user and `registrationIdempotencyKey` are reused.
- It is still unclear whether the same `activationToken` may register multiple devices.
- It is still unclear whether duplicate `deviceFingerprintHash` with a new `registrationIdempotencyKey` should create a new device, reject with conflict, or rotate the existing token.
- Consent update policy is not fully settled. Current behavior appends a consent row instead of updating an older row.

# Agent AS contract/web QA follow-up

Updated: 2026-07-02

## Security boundary

- Web Agent/RAG session APIs stay on web JWT:
  - `POST /api/agent/sessions`
  - `POST /api/agent/sessions/{id}/run`
  - `GET /api/agent/sessions/{id}`
- PC Agent token security applies only to PC Agent lifecycle endpoints:
  - `POST /api/agent/devices/register`
  - `POST /api/agent/consents`
  - `POST /api/agent/heartbeat`
  - `POST /api/agent/log-uploads`
- `POST /api/agent/devices/register` remains bootstrap-only and must not receive an Authorization header.
- `/api/agent-logs/upload` remains the web JWT/manual upload path. `/api/agent/log-uploads` is the PC Agent token upload path.

## Demo account and download notes

- Demo user/admin credentials must be prepared from the auth seed or shared by the integration owner before QA. Do not hard-code new demo credentials in the web app.
- Latest main provides a demo `agent.exe` download at `/downloads/pc-agent/agent.exe`, backed by `apps/web/public/downloads/pc-agent/agent.exe`.
- The sample JSONL download remains a manual AS upload fallback for QA environments where the Windows runtime is not used.
- When `agent.exe` changes, verify the web download path, `README.txt`, SHA256, and the local `agent-config.json` path documented by the PC Agent runtime.

## Main merge verification commands

Run these after every main merge touching Agent AS, support UI, or OpenAPI:

```powershell
python tools\validate_openapi.py
cd apps\api
.\gradlew.bat test --tests com.buildgraph.prototype.config.security.AgentSecurityChainTest --no-daemon
.\gradlew.bat test --tests com.buildgraph.prototype.config.security.PcAgentControllerSecurityTest --no-daemon
.\gradlew.bat test --tests com.buildgraph.prototype.agent.PcAgentAsServiceTest --no-daemon
cd ..\..
cd apps\web
npm run build
npm run test -- --reporter=dot
```
## Agent AS contract/web/QA P1-P3 completion

- 기준 브랜치: `integration/agent-as-e2e`
- 완료 범위:
  - P1: 사용자 `/support/{ticketId}` 화면 QA, 관리자 `supportDecision`/`reviewStatus`/`riskLevel`/`adminNote`/`remoteSupportLink` 저장 UI, 저장 후 사용자 화면 반영 Playwright QA, demo USER/ADMIN 계정 및 테스트 데이터 절차 문서화, 최신 main의 `agent.exe` 다운로드와 샘플 JSONL fallback을 runbook에 고정.
  - P2: `RULE_READY`, `REQUIRED`, `REMOTE_POSSIBLE`, `NEEDS_MORE_INFO` 및 관련 support enum 한글 배지 표시, 사용자/관리자 AS 화면 raw enum 노출 축소, 데모용 업로드 실패 메시지 유지, frontend DTO와 backend/OpenAPI 필드 불일치 방지 테스트 보강.
  - P3: `tools/validate_openapi.py`가 PC Agent token 경계와 AS ticket decision schema를 검증하도록 강화, OpenAPI client generation 검토 결과 문서화, 운영 장애 대응/runbook/발표 시나리오 정리.
- 추가 문서:
  - `docs/agent-as/DEMO_RUNBOOK.md`
  - `docs/agent-as/README.md` runbook 링크
- 실행 검증:
  - `git diff --check`: pass
  - `C:\Users\82103\anaconda3\python.exe tools\validate_openapi.py`: pass, 67 paths
  - `cd apps\api; .\gradlew.bat test --tests com.buildgraph.prototype.ticket.TicketQueryServiceTest --tests com.buildgraph.prototype.config.security.AgentSecurityChainTest --tests com.buildgraph.prototype.config.security.PcAgentControllerSecurityTest --tests com.buildgraph.prototype.agent.PcAgentAsServiceTest --no-daemon`: pass
  - `cd apps\api; .\gradlew.bat test --no-daemon`: pass
  - `cd apps\web; npm run build`: pass
  - `cd apps\web; npm run test -- --reporter=dot`: pass, 74 tests
- 제외/주의:
  - Quick Assist 직접 실행, Windows Service, signed installer, auto-update, release channel 운영은 범위 밖으로 유지.
  - sandbox 내부에서 Gradle wrapper와 Playwright proxy가 네트워크 권한에 막혀, 필요한 테스트는 승인된 escalated 실행으로 검증했다.
