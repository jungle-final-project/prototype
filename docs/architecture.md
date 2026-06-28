# 아키텍처

이 저장소는 데스크톱 웹, Spring Boot API, PC Agent, Docker 인프라를 하나의 모노레포로 묶은 프로토타입입니다. 각 담당자는 자기 feature/domain을 구현하고, 공통 계약은 `API_CONTRACT.md`, `DB_SCHEMA.md`, `ROUTE_OWNERSHIP.md`, OpenAPI, CI로 맞춥니다.

## 전체 구성

```text
apps/
  web       React/Vite 프론트엔드
  api       Spring Boot API
  pc-agent  Python JSONL 로그 생성 CLI
infra/
  docker    PostgreSQL 초기화 등 인프라 설정
docs/
  API_CONTRACT.md, DB_SCHEMA.md, ROUTE_OWNERSHIP.md, openapi.yaml, 역할/체크리스트/결정사항 문서
seed/
  샘플 데이터와 Agent 로그
```

## 런타임 흐름

1. 사용자가 웹에서 용도와 예산을 입력합니다.
2. 프론트엔드는 `/api/requirements/parse`로 요구사항 파싱을 요청합니다.
3. API는 시드 기반 추천 빌드와 도구 검증 근거를 반환합니다.
4. 사용자는 추천 결과, 부품 변경 비교, 목표가 알림, AS 접수 흐름으로 이동합니다.
5. PC Agent는 최근 30분 JSONL 로그를 만들어 AS 업로드 테스트에 사용합니다.
6. 관리자는 Agent/RAG/Tool 근거, 부품/가격, AS 티켓을 관리자 화면에서 확인합니다.

## 주요 서비스

| 서비스 | 역할 | 포트 |
| --- | --- | --- |
| web | React 개발 서버 | `5173` |
| api | Spring Boot API | `8080` |
| postgres | PostgreSQL + pgvector | `5432` |
| redis | 캐시/작업 상태 확장용 | `6379` |
| rabbitmq | 비동기 작업 확장용 | `5672`, `15672` |
| mailpit | 메일 발송 테스트 | `1025`, `8025` |

## 백엔드 도메인

| 도메인 | 패키지 | 담당 흐름 |
| --- | --- | --- |
| user | `.../user` | 로그인, 회원가입, 현재 사용자 |
| build | `.../build` | 요구사항 파싱, 추천 빌드, 부품 변경 |
| part | `.../part` | 부품 목록, 부품 상세, Tool check |
| price | `.../price` | 목표가 알림, 가격 수집 작업 |
| agent | `.../agent` | Agent 세션, 상태 전이 |
| rag | `.../rag` | 근거 검색, 근거 상세 |
| log | `.../log` | Agent 로그 업로드 |
| ticket | `.../ticket` | AS 티켓 |
| admin | `.../admin` | 관리자 dashboard와 운영 조회 |

## 프론트엔드 구조

| 영역 | 경로 | 설명 |
| --- | --- | --- |
| 공통 layout/display/feedback | `apps/web/src/components` | shell, table, badge, panel 등 |
| 견적/추천 | `apps/web/src/features/quote` | 홈, 요구사항 입력, 추천 결과, 부품 변경, 내 견적 |
| 부품/가격 | `apps/web/src/features/parts` | 셀프 견적, 부품 표, 도구 검사 |
| 인증 | `apps/web/src/features/auth` | 로그인, 회원가입 |
| AS | `apps/web/src/features/support` | AS 접수, 티켓 상세 |
| 관리자 | `apps/web/src/features/admin` | dashboard, Agent/RAG/Tool, 부품, AS 티켓 |

## Health Endpoint

CI와 팀 로컬 확인은 `/api/health`를 기준으로 합니다. 이 경로는 Actuator 기본 경로가 아니라 [HealthController.java](../apps/api/src/main/java/com/buildgraph/prototype/common/HealthController.java)가 제공하는 프로젝트용 health endpoint이며 DB 연결까지 확인합니다.
