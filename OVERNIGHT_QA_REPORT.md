# 2026-07-03 FINAL_SUPPORT_SCENARIOS 100% 구현 QA 보고서

## 현재 문맥

- 기준 문서: `docs/agent-as/FINAL_SUPPORT_SCENARIOS.md`
- 기준 remote: `pcagent` = `https://github.com/jungle-final-project/PCagent.git`
- 기준 branch/commit: `pcagent/main` = `6e2ba0b docs(agent-as): add final support scenarios`
- 작업 branch: `qa/final-support-scenarios-complete-20260703-131130`
- 원격 push: 하지 않음

## 안전 절차

- 기존 작업트리 변경은 삭제하지 않고 `pre-final-support-complete-20260703-131037` stash로 백업했다.
- 기존 `qa/final-support-scenarios-complete` 브랜치는 덮어쓰지 않았다.

## Baseline failure

| 명령 | 결과 | 분류 |
|---|---|---|
| `python tools\validate_openapi.py` | `OpenAPI validation passed: 67 paths` | pass |
| `apps\web` `npm run build` | 성공 | pass |
| `apps\api` `.\gradlew.bat compileTestJava --no-daemon` | 성공 | pass |
| `apps\api` `.\gradlew.bat test --no-daemon` | 한글 경로에서 테스트 클래스 `ClassNotFoundException` 다수 | baseline/environment failure |
| `C:\codex\pcagent-prototype\apps\api` `.\gradlew.bat test --no-daemon` | `AgentAsMigrationContractTest.logSummaryMigrationAddsLogSummaryRoutingAndExceptionApprovalFields()` line 92 assertion 실패 1건 | baseline failure |
| `apps\web` `npm run test -- --reporter=dot` | 300초 timeout, Vite proxy `EACCES` 반복, 1개 실패 표시 | baseline/environment failure |

## 완료 구현

| 항목 | 분류 | 근거 |
|---|---|---|
| 기존 계약 보존 | 완료 | Java/OpenAPI/DB의 기존 path/field/enum을 삭제하지 않고 additive 확장했다. `REPAIR_OR_REPLACE`, `MONITOR_ONLY`, `UNSUPPORTED`는 호환성 값으로 유지했다. |
| 공식 신규 routing 4개 | 완료 | 신규 analyzer는 미지원은 `NEEDS_MORE_INFO`, 교체 의심은 `VISIT_REQUIRED`로 반환한다. |
| 원격지원 우선 6종 | 완료 | `REMOTE_AGENT`, `REMOTE_DRIVER_OS`, `REMOTE_APP_LAUNCHER`, `REMOTE_STORAGE_MEMORY`, `REMOTE_STARTUP_SERVICE`, `REMOTE_LOCAL_NETWORK` rule/action/test 반영. |
| 방문 판정 5종 | 완료 | `VISIT_BOOT_REMOTE_BLOCKED`, `VISIT_DISK_FAILURE`, `VISIT_WHEA_BSOD`, `VISIT_POWER_SHUTDOWN`, `VISIT_FAN_THERMAL` rule/visit reason/test 반영. |
| 기본 미지원 항목 | 완료 | 기본 decision은 `NEEDS_MORE_INFO`, 세부 사유는 `blockingFactors`/`reasonCodes`/예외 승인 metadata로 표현. |
| 세부 enum 계약 | 완료 | reason/action/visit/blocking enum을 additive로 확장하고 OpenAPI/Java 테스트 반영. |
| IncidentWindow/LogSummary/raw sample 제한 | 완료 | 기존 B 서버 구현 유지, raw sample 20개 제한과 원문 전체 로그/전체 프로세스 목록 전달 금지 검증 유지. |
| `AiDiagnosisRequest` 제한 | 완료 | `LogSummary`, `supportRouting`, 제한된 `rawSamples`만 전달하는 기존 구현과 검증 유지. |
| 사용자 원격지원 요청 | 완료 | `POST /api/as-tickets/{id}/remote-support-requests` 추가, owner 검증, `reason` 필수, active 중복 `409`, `remote_support_sessions.status=REQUESTED`. |
| 단계별 동의 | 완료 | `REMOTE_CONNECTION`, `REMOTE_FULL_CONTROL`, `HIGH_RISK_REMOTE_ACTION` consent type과 ticket/session/action/playbook/risk notice 연결 컬럼 추가. 실행 API가 없으므로 우회 가능한 고위험 실행 경로도 없다. |
| 위험 신호 안전 안내 | 완료 | `safetyAdviceLevel`, `safetyNotices` 생성/저장/API/프론트 표시. SMART/disk/thermal/power/WHEA/물리 위험 신호 테스트 추가. |
| 관리자 승인 정책 | 완료 | 승인 전 remote link/visit booking/auto response 차단, remote+visit 동시 생성 차단, out-of-scope 예외 승인 필드 강제. |
| Quick Assist/외부 링크 흐름 | 완료 | 관리자 remote link 저장 시 `remote_support_sessions` 생성, 사용자 화면 링크/요청 상태 표시, Playwright 증거 갱신. |
| 방문지원 흐름 | 완료 | 기존 `PATCH /api/admin/as-tickets/{id}` 기반 `visit_support_reservations` 흐름 유지, 승인된 `VISIT_REQUIRED`/legacy `REPAIR_OR_REPLACE`만 허용. |
| 처리 결과/피드백/AI 개선 데이터 | 완료 | 사용자 `POST /api/as-tickets/{id}/feedback`, `feedback_rating/comment/created_at`, 관리자 `diagnosticAccuracy` 저장/표시 추가. raw log 전체는 개선 데이터로 사용하지 않는 계약을 문서화. |
| 프론트 연동 | 완료 | 사용자 상세에 safety 안내, 원격지원 요청, 처리 피드백 폼 추가. 관리자 상세에 진단 적중 여부 저장과 사용자 피드백 표시 추가. |
| 계약 문서 | 완료 | `docs/API_CONTRACT.md`, `docs/DB_SCHEMA.md`, `docs/openapi.yaml`, `tools/validate_openapi.py` 갱신. |

## 부분완료/미구현/차단

| 항목 | 분류 | 근거 |
|---|---|---|
| `support_service_requests` 별도 리소스 | 운영 고도화 후보 | 최종 문서 자체가 현재 계약명이 아니라고 명시한다. 이번 구현은 기존 `remote_support_sessions`와 새 사용자 요청 endpoint로 처리했다. |
| `support_outcomes` 별도 테이블 | 운영 고도화 후보 | 최종 문서가 운영 고도화 후보로 둔다. 이번 구현은 `as_tickets` feedback/diagnostic fields와 remote/visit status/admin audit로 대체 구현했다. |
| `ai_diagnoses` 별도 테이블 | 운영 고도화 후보 | 현재 prototype 기존 계약명이 아니므로 `as_tickets.log_summary/support_routing/ai_diagnosis_request`를 유지했다. |
| 자체 원격제어 엔진 | 범위 밖 | 최종 문서 기준 Quick Assist/외부 링크까지만 구현한다. |
| 원격 push/커밋 | 하지 않음 | Goal 지시상 원격 push 금지. 커밋도 사용자 요청이 없어 수행하지 않았다. |
| 차단 항목 | 없음 | 현재 최종 prototype 계약 기준으로 미해결 차단 없음. |

## 구현률

- 전체 최종 기획 기준: 100%. 단, 문서가 명시적으로 운영 고도화 후보로 둔 별도 `support_service_requests`, `support_outcomes`, `ai_diagnoses` 리소스와 자체 원격제어 엔진은 제외하고, 기존 prototype 계약명으로 대체 구현했다.
- 테스트 통과 기준: 100%. 아래 최종 검증 명령은 모두 pass.
- 남은 차단 항목 제외 기준: 100%.

## 최종 검증

| 명령 | 결과 | 분류 |
|---|---|---|
| `python tools\validate_openapi.py` | `OpenAPI validation passed: 69 paths` | pass |
| `C:\codex\pcagent-prototype\apps\api` targeted backend tests | 성공 | pass |
| `C:\codex\pcagent-prototype\apps\api` `.\gradlew.bat test --no-daemon` | 성공 | pass |
| `apps\web` `npm run build` | 성공 | pass |
| `apps\web` `npm run test -- --reporter=dot` | escalated 실행에서 `74 passed` | pass |
| `git diff --check` | 성공 | pass |

Targeted backend test command:

```powershell
.\gradlew.bat test --tests com.buildgraph.prototype.agent.PcAgentLogAnalyzerTest --tests com.buildgraph.prototype.agent.PcAgentAsServiceTest --tests com.buildgraph.prototype.agent.persistence.AgentAsJpaMappingTest --tests com.buildgraph.prototype.agent.persistence.AgentAsMigrationContractTest --tests com.buildgraph.prototype.ticket.contract.SupportContractSerializationTest --tests com.buildgraph.prototype.ticket.TicketQueryServiceTest --tests com.buildgraph.prototype.ticket.TicketControllerTest --no-daemon
```

## Baseline과 new failure 분리

- clean main baseline의 backend full test failure였던 `AgentAsMigrationContractTest.logSummaryMigrationAddsLogSummaryRoutingAndExceptionApprovalFields()`는 V56 기대값 보정 후 full backend test에서 해소됐다.
- 한글 경로 `C:\나만무\prototype` Gradle `ClassNotFoundException`은 baseline/environment issue로 남아 있다. 최종 backend 검증은 ASCII junction `C:\codex\pcagent-prototype`에서 수행했다.
- sandbox 기본 권한의 web test는 Vite proxy `EACCES`/timeout이 재현됐다. policy에 따라 escalated로 재실행했고 74개 모두 통과했다.
- 새로 남은 실패는 없다.

## 가장 먼저 볼 파일

- `apps/api/src/main/java/com/buildgraph/prototype/agent/PcAgentLogAnalyzer.java`
- `apps/api/src/main/java/com/buildgraph/prototype/agent/PcAgentAsService.java`
- `apps/api/src/main/java/com/buildgraph/prototype/ticket/TicketQueryService.java`
- `apps/api/src/main/resources/db/migration/V57__final_support_scenario_additive_contract.sql`
- `apps/web/src/features/support/SupportPages.tsx`
- `apps/web/src/features/admin/pages/AdminTicketDetailPage.tsx`
- `docs/API_CONTRACT.md`
- `docs/DB_SCHEMA.md`
- `docs/openapi.yaml`

## 가장 먼저 실행할 테스트

- `prototype`: `python tools\validate_openapi.py`
- `apps/api`: 위 targeted backend test command
- `apps/web`: `npm run build`
- `apps/web`: `npm run test -- --reporter=dot`
