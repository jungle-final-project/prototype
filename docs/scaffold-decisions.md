# 스캐폴드 결정사항

이 문서는 팀원이 현재 구조의 결정 배경을 빠르게 이해하기 위한 결정사항 모음입니다. 기획안 자체를 대체하지 않으며, 이번 Sprint에서 함께 지킬 기준과 이후 작업으로 넘긴 항목을 정리합니다.

## 이번 Sprint 기준

현재 저장소는 5인 병렬 개발을 시작하기 위한 공통 기반입니다. 이번 Sprint에서는 아래 항목을 먼저 맞춥니다.

- 데스크톱 기준 사용자/관리자 화면 출발점
- React 프론트 라우팅과 route smoke test
- Spring Boot 도메인 controller skeleton
- `API_CONTRACT.md`, `DB_SCHEMA.md`, `ROUTE_OWNERSHIP.md` 기반 공통 계약
- PostgreSQL, Redis, RabbitMQ, Mailpit, Docker Compose 공통 환경
- PC Agent JSONL sample/export CLI
- GitHub Actions 기본 검증

## 이후 Sprint 작업

아래 항목은 기능 담당자가 구현을 진행하면서 단계적으로 확장합니다.

- 결제, 배송, 주문
- 자체 원격제어
- 최저가 보장과 실제 크롤링
- 정확한 FPS 예측
- LLM/RAG 품질 개선
- 하드웨어 센서 상시 수집
- 모든 response schema 상세화
- 300명/1000명 부하 테스트 완성본
- 모바일/반응형 완성도

## API 결정

- API 요청/응답 계약은 [API_CONTRACT.md](API_CONTRACT.md)를 기준으로 하고, [openapi.yaml](openapi.yaml)은 기계 검증용으로 함께 유지합니다.
- DB table/column/enum/status 계약은 [DB_SCHEMA.md](DB_SCHEMA.md)를 기준으로 합니다.
- route/API/file owner 계약은 [ROUTE_OWNERSHIP.md](ROUTE_OWNERSHIP.md)를 기준으로 합니다.
- 백엔드 controller skeleton은 seed demo 안정성을 위해 `@RequestBody(required = false)`를 일부 사용합니다.
- OpenAPI는 성공 요청 계약을 명확히 하기 위해 주요 POST requestBody를 `required: true`로 둡니다.
- 실제 DTO/service 전환 시 각 담당자가 validation과 error response를 확정합니다.

## Tool API 결정

현재 스캐폴드의 Tool API는 공통 계약서에 맞춰 5개 개별 경로를 사용합니다.

```text
POST /api/tools/compatibility/check
POST /api/tools/power/check
POST /api/tools/size/check
POST /api/tools/performance/check
POST /api/tools/price/check
```

사용 가능한 `tool` 값:

- `compatibility`
- `power`
- `size`
- `performance`
- `price`

직접 Tool check 호출은 `tool_invocations`에 저장하지 않습니다. `tool_invocations`에는 Agent/recommend 내부에서 실행된 Tool 호출 이력만 저장합니다.

## Health 결정

CI runtime smoke는 `/api/health`를 기준으로 합니다. 이 경로는 Spring Boot Actuator 기본 경로가 아니라 `HealthController`가 제공하는 프로젝트용 endpoint입니다.

확인 내용:

- API jar 실행
- PostgreSQL 연결
- `/api/health` 응답

정상 응답 예:

```json
{"database":"UP","status":"UP"}
```

## Barrel 파일 규칙

아래 파일은 구현을 모으는 파일이 아니라 export 편의를 위한 barrel입니다.

| 파일 | 규칙 |
| --- | --- |
| `apps/web/src/features/quote/QuotePages.tsx` | quote page export만 유지 |
| `apps/web/src/features/admin/AdminPages.tsx` | admin page export만 유지 |
| `apps/web/src/data/prototypeData.ts` | feature mock 재export만 유지 |
| `apps/web/src/components/ui.tsx` | 공통 UI export만 유지 |

새 화면, mock, 컴포넌트 구현은 담당 feature 내부에 추가합니다.

## 담당자별 확장 후보

- 각 domain DTO/service/repository 분리
- OpenAPI response schema 상세화
- Tool별 request schema 엄격화
- 인증/관리자 권한 처리
- Redis/RabbitMQ worker 연결
- PC Agent 실제 센서 수집
- k6 부하 테스트 시나리오 확장
