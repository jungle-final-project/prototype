# 2026-07-03 FINAL_SUPPORT_SCENARIOS 100% 구현 Goal

## Current goal

- `docs/agent-as/FINAL_SUPPORT_SCENARIOS.md`의 최종 기획을 기준으로 PC Agent AS 기능을 구현한다.
- 기존 공통 계약(`docs/API_CONTRACT.md`, `docs/DB_SCHEMA.md`, `docs/openapi.yaml`, Flyway, Java public enum/DTO/API 계약)은 삭제/변경/축소하지 않고, 필요한 항목만 additive로 확장한다.
- 원격 push는 하지 않는다.

## Done

- Goal objective 파일 `C:\Users\whqja\.codex\attachments\ee35ec0c-ac83-440c-8454-58287709c08d\goal-objective.md`를 UTF-8로 읽었다.
- `skill-router` 기준 primary skill을 `goal-handoff`로 선택했고, `git-workflow`, `code-review`, `wiki-writing`을 보조로 확인했다.
- 기준 원격 `https://github.com/jungle-final-project/PCagent.git`가 `pcagent` remote임을 확인했다.
- 기존 작업트리 변경은 `pre-final-support-complete-20260703-131037` stash로 백업했다.
- `git fetch pcagent --prune` 성공 후 `pcagent/main`이 `6e2ba0b docs(agent-as): add final support scenarios`임을 확인했다.
- 기존 `qa/final-support-scenarios-complete` 브랜치를 덮어쓰지 않고 `qa/final-support-scenarios-complete-20260703-131130` 브랜치를 `pcagent/main` 기준으로 생성했다.
- `docs/agent-as/FINAL_SUPPORT_SCENARIOS.md` 존재를 확인했다.
- clean main 기준 baseline 검증을 실행했다.

## Baseline verification

- `python tools\validate_openapi.py`: 성공, `OpenAPI validation passed: 67 paths`.
- `apps\web`: `npm run build` 성공.
- `apps\api`: `.\gradlew.bat compileTestJava --no-daemon` 성공.
- `apps\api`: 한글 경로 `C:\나만무\prototype`에서 `.\gradlew.bat test --no-daemon` 실패. 원인은 여러 테스트 클래스 `ClassNotFoundException`으로, assertion 실패 전 단계다.
- ASCII junction `C:\codex\pcagent-prototype`에서 `.\gradlew.bat test --no-daemon` 실패. clean main baseline failure는 `AgentAsMigrationContractTest.logSummaryMigrationAddsLogSummaryRoutingAndExceptionApprovalFields()` line 92 assertion 1건이다.
- `apps\web`: `npm run test -- --reporter=dot`는 300초 timeout. 실행 중 1개 실패 표시와 Vite proxy `EACCES` 로그가 반복됐다.

## Done in current implementation pass

- 최종 시나리오 기준으로 analyzer가 신규 흐름에서는 `SELF_SOLVABLE`, `REMOTE_POSSIBLE`, `VISIT_REQUIRED`, `NEEDS_MORE_INFO`만 생성하도록 보정했다. 기존 `REPAIR_OR_REPLACE`, `MONITOR_ONLY`, `UNSUPPORTED` enum은 호환성 때문에 삭제하지 않았다.
- 원격 6종, 방문 5종, 기본 미지원 항목의 reason/action/visit/blocking enum을 additive로 확장했다.
- `safetyAdviceLevel`, `safetyNotices`, 단계별 consent type, 사용자 원격지원 요청 API, 사용자 feedback API, 관리자 `diagnosticAccuracy`를 코드/OpenAPI/API/DB 문서/Flyway에 추가했다.
- 사용자 `/support/{ticketId}` 화면에 위험 안내, 원격지원 요청, 처리 피드백 저장을 연결했다.
- 관리자 AS 상세 화면에 진단 적중 여부 저장과 사용자 피드백 표시를 연결했다.
- Playwright visual fixture와 QA screenshots를 최종 IncidentWindow/안전 안내/원격지원 요청/피드백 흐름 기준으로 갱신했다.

## Final verification

- `python tools\validate_openapi.py`: 성공, `OpenAPI validation passed: 69 paths`.
- `C:\codex\pcagent-prototype\apps\api` `.\gradlew.bat test --tests com.buildgraph.prototype.agent.PcAgentLogAnalyzerTest --tests com.buildgraph.prototype.agent.PcAgentAsServiceTest --tests com.buildgraph.prototype.agent.persistence.AgentAsJpaMappingTest --tests com.buildgraph.prototype.agent.persistence.AgentAsMigrationContractTest --tests com.buildgraph.prototype.ticket.contract.SupportContractSerializationTest --tests com.buildgraph.prototype.ticket.TicketQueryServiceTest --tests com.buildgraph.prototype.ticket.TicketControllerTest --no-daemon`: 성공.
- `C:\codex\pcagent-prototype\apps\api` `.\gradlew.bat test --no-daemon`: 성공.
- `apps\web` `npm run build`: 성공.
- `apps\web` `npm run test -- --reporter=dot`: escalated 실행에서 74 passed.
- `git diff --check`: 성공.

## Remaining risks

- 한글 경로 `C:\나만무\prototype`에서 Gradle full test가 `ClassNotFoundException`으로 실패하는 baseline/environment 문제가 있어 backend test는 ASCII junction `C:\codex\pcagent-prototype`에서 검증했다.
- sandbox 기본 권한에서는 Playwright가 Vite proxy `EACCES`로 timeout될 수 있어, 최종 프론트 테스트는 escalated 환경에서 검증했다.
- 원격 push와 커밋은 하지 않았다.

## Runtime check

- `docker --version`: Docker CLI 확인됨. 단, `C:\Users\whqja\.docker\config.json` 접근 경고가 표시됐다.
- `docker compose config --quiet`: 성공.
- `localhost:8080`, `localhost:5173`: 현재 포트는 비어 있음.
- `docker compose up --build -d`: 실패. 원인은 Docker Desktop Linux engine daemon 미실행(`npipe:////./pipe/dockerDesktopLinuxEngine` 없음).
- 바로 화면 확인하려면 Docker Desktop을 켠 뒤 `docker compose up --build`를 다시 실행해야 한다.

# 2026-07-03 Agent AS B 서버 LogSummary/routing 구현

## Current goal

- `docs/agent-as/FINAL_SUPPORT_SCENARIOS.md` 기준 B 담당 범위만 구현한다.
- 업로드 RawLog 검증, IncidentWindow 기반 LogSummary, rule routing, 관리자 최종 decision/예외 승인, LLM 요청 payload 제한을 서버에서 처리한다.
- 커밋/푸시는 사용자 승인 전까지 진행하지 않는다.

## Done

- `PcAgentLogAnalyzer`를 추가해 RawLog 필수 필드 검증, JSONL 한 줄 파싱 실패 시 전체 실패, raw path 미마스킹 reject, event message 마스킹, IncidentWindow 필터링, rule 기반 `LogSummary`/`supportRouting`/`AiDiagnosisRequest` 생성을 구현했다.
- 원격 6종, 방문 5종, 미지원 7종 routing rule을 추가했다.
- `rawSamples`는 `evidenceRefs`와 연결된 로그만 최대 20개로 제한하고, sample payload의 긴 list를 잘라 전체 프로세스 목록이 LLM 요청에 들어가지 않게 했다.
- `/api/agent/log-uploads`에서 최근 30분 고정 대신 증상별 IncidentWindow를 저장/연결하고, `agent_log_uploads`, `as_tickets`에 summary/routing/AI 요청 JSONB를 저장하도록 변경했다.
- 관리자 `PATCH /api/admin/as-tickets/{id}`에서 `UNSUPPORTED` 기본 예약 차단과 예외 승인 필수 필드, audit metadata, 전환 후 decision 저장을 구현했다.
- JPA entity, support decision enum, Flyway V56 migration, OpenAPI/API/DB 문서를 갱신했다.
- 관련 단위 테스트를 추가/수정했다.

## Remaining issues

- 한글 경로 `C:\나만무\prototype`에서 Gradle test worker가 테스트 클래스를 `ClassNotFoundException`으로 로드하지 못하는 기존 문제가 재현됐다. 테스트 클래스 산출물은 존재하고 `javap` 로드는 가능하지만, Gradle `test` 태스크가 실행 단계 전에 중단된다.
- 커밋/푸시는 하지 않았다.

## Last verification

- `cd apps\api; .\gradlew.bat compileTestJava --no-daemon` 성공.
- `python tools\validate_openapi.py` 성공. 결과: `OpenAPI validation passed: 67 paths`.
- `git diff --check` 성공.
- 변경 범위 테스트 명령은 실패: `.\gradlew.bat test --tests PcAgentLogAnalyzerTest --tests PcAgentAsServiceTest --tests TicketQueryServiceTest --tests AgentAsJpaMappingTest --tests AgentAsMigrationContractTest --no-daemon`. 원인은 각 테스트 클래스 `ClassNotFoundException`이며 assertion 실패는 확인되지 않았다.

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

## PC Agent exe runtime wiring check

Updated: 2026-07-03

- 확인 목표: `agent.exe`가 PC metric JSONL을 만들고 `/api/agent/log-uploads`로 올린 뒤, 생성된 AS ticket이 웹 `/support/{ticketId}`에서 조회되는지 확인.
- 실행 상태:
  - 기존 compose DB는 Flyway checksum mismatch로 API가 실패했다.
  - 기존 DB 볼륨은 삭제하지 않고, 임시 컨테이너 `pcagent-demo-postgres`, `pcagent-demo-api`, `pcagent-demo-web`로 런타임을 확인했다.
  - `http://localhost:8080/api/health`: pass
  - `http://localhost:5173/api/health`: pass
  - 웹 다운로드 `http://localhost:5173/downloads/pc-agent/agent.exe` SHA256이 repo 파일과 일치: `44791862BD8A33869F7D33891078FDACAFC180AC11EC87F801EE0EE6EE198456`
- 수정 내용:
  - `apps/pc-agent/buildgraph_agent.py`: Agent JSONL row에 서버 필수 envelope(`schemaVersion`, `collectedAt`, `agentId`, `sequence`, `kind`, `payload`, `privacyFlags`)를 추가.
  - `apps/pc-agent/buildgraph_agent.py`: multipart text field에 `Content-Type: text/plain; charset=utf-8` 추가.
  - `apps/api/src/main/java/com/buildgraph/prototype/agent/PcAgentAsService.java`: Spring multipart text decoding 후 깨진 UTF-8로 보이는 값을 한글로 복원.
  - `apps/web/public/downloads/pc-agent/agent.exe`: 수정된 PC Agent로 재빌드 후 교체.
- 검증:
  - `python -m pytest apps\pc-agent -q`: pass, 24 tests
  - `.\gradlew.bat test --tests com.buildgraph.prototype.agent.PcAgentAsServiceTest --no-daemon`: pass
  - `agent.exe register --config artifacts\runtime\pcagent-demo\agent-config.json`: pass
  - `agent.exe collect --config ... --iterations 1`: pass
  - `POST /api/agent/consents`: pass
  - `POST /api/agent/heartbeat`: pass
  - `agent.exe upload --config ... --symptom-type REMOTE_DRIVER_OS --no-open`: pass, latest ticket `2116e340-6534-4769-a773-d0e90d36f8bb`
  - DB 직접 확인: latest ticket `symptom` is stored as `게임 중 화면 드라이버 경고와 발열이 있습니다.`
- 남은 주의:
  - 현재 PC Agent는 Windows Service/installer가 아니라 MVP용 CLI/트레이 실행이다.
  - 더블클릭 트레이의 로그 뷰어는 PyInstaller 빌드 중 tkinter broken 경고가 있어 별도 GUI 확인이 필요하다. CLI register/collect/upload는 정상 확인했다.
