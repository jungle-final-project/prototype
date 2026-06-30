# k6 Smoke Report Template

이 문서는 Sprint 1 k6 smoke 실행 결과를 PR에 일관되게 남기기 위한 템플릿이다.

이 템플릿은 300명/1,000명 부하 테스트 결과가 아니다. Sprint 1에서는 `infra/k6/smoke.js` 기준으로 API 연결, 주요 endpoint 응답, 기본 threshold 통과 여부만 기록한다.

## Report Metadata

| 항목 | 값 |
| --- | --- |
| 테스트 일시 | `<YYYY-MM-DD HH:mm KST>` |
| 실행자 | `<name>` |
| PR/브랜치 | `<PR number or branch>` |
| commit | `<git rev-parse --short HEAD>` |
| 실행 환경 | `<local Docker Compose / CI / other>` |
| BASE_URL | `<http://localhost:8080>` |
| k6 script | `infra/k6/smoke.js` |

## Runtime Preconditions

| 항목 | 결과 | 근거 |
| --- | --- | --- |
| `docker compose config` | `<PASS/FAIL>` | `<command output summary>` |
| `docker compose up --build` | `<PASS/FAIL/N/A>` | `<service status summary>` |
| `/api/health` | `<PASS/FAIL>` | `<status/database response>` |
| API auth/test seed 상태 | `<PASS/FAIL/N/A>` | `<JWT token or seed note>` |

## k6 Command

```bash
k6 run infra/k6/smoke.js
```

BASE_URL을 바꿔야 하면 아래처럼 실행한다.

```bash
BASE_URL=http://localhost:8080 k6 run infra/k6/smoke.js
```

## Scenario

| 항목 | 값 |
| --- | --- |
| scenario | `smoke` |
| executor | `constant-vus` |
| vus | `5` |
| duration | `30s` |
| sleep | `1s` |

## Endpoint Checks

| endpoint | method | expected | result | note |
| --- | --- | --- | --- | --- |
| `/api/health` | `GET` | `200` | `<PASS/FAIL>` | API와 DB 연결 smoke |
| `/api/builds/recommend` | `POST` | `200` | `<PASS/FAIL>` | 추천 흐름 mock smoke |
| `/api/parts` | `GET` | `200` | `<PASS/FAIL>` | 부품 조회 smoke |

## Threshold Results

| metric | threshold | result | pass |
| --- | --- | --- | --- |
| `http_req_failed` | `rate < 0.01` | `<value>` | `<YES/NO>` |
| `http_req_duration` | `p95 < 500ms` | `<value>` | `<YES/NO>` |

## Summary Metrics

| metric | value |
| --- | --- |
| total requests | `<count>` |
| checks passed | `<count or percent>` |
| checks failed | `<count or percent>` |
| avg latency | `<ms>` |
| p95 latency | `<ms>` |
| max latency | `<ms>` |
| error rate | `<rate>` |

## Failure Details

| 실패 항목 | 증상 | 원인 후보 | owner | 후속 조치 |
| --- | --- | --- | --- | --- |
| `<endpoint or threshold>` | `<status/error>` | `<suspected cause>` | `<owner>` | `<next action>` |

## Bottleneck Notes

- `<DB connection / API latency / seed data / Docker resource / endpoint owner issue>`

## Follow-up

| 우선순위 | 작업 | owner | 이슈/PR |
| --- | --- | --- | --- |
| P0 | `<must fix before merge>` | `<owner>` | `<link>` |
| P1 | `<follow-up>` | `<owner>` | `<link>` |

## PR Checklist Snippet

```text
k6 smoke:
  script: infra/k6/smoke.js
  baseUrl: <BASE_URL>
  result: <PASS/FAIL>
  http_req_failed: <value> / threshold rate<0.01
  http_req_duration_p95: <value> / threshold p95<500ms
  failed endpoints:
    - <none or endpoint>
  report: docs/reports/<YYYY-MM-DD>-k6-smoke.md
```

## Scope Notes

- 이 리포트는 smoke 결과 기록용이다.
- 300명/1,000명 부하 테스트 시나리오는 별도 문서와 별도 k6 script로 확장한다.
- 결과는 DB에 저장하지 않고 k6 리포트 파일로 관리한다.
- endpoint request/response 구조를 바꾸면 담당 owner와 `docs/API_CONTRACT.md`, `docs/openapi.yaml` 변경 여부를 먼저 확인한다.
