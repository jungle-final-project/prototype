# 전체 사용자 시나리오 감사 오류 수정 결과

- 검증일: 2026-07-21
- 기준 커밋: `23226ec5ead7fe3a3c8ceb14adc81330f7b783b3`
- 작업 브랜치: `codex/fix-user-scenario-audit-20260721`
- 범위: 전체 사용자 시나리오 감사에서 재현된 독립 오류 6건

## 결과 요약

감사에서 확정한 독립 오류 6건을 수정했다. 결제, Build Chat 문맥, RAG fallback, 파워 정격 조건, API 계약, 모바일 화면을 각각 회귀 테스트로 고정했다. 최신 `origin/main`과 작업 기준 커밋이 같은 것도 최종 확인했다.

| ID | 심각도 | 문제 | 수정 결과 |
|---|---:|---|---|
| BG-FIX-01 | P1 | 기사 제안 선택 후 결제 시도 생성이 JDBC `Timestamp` 변환 오류로 500 반환 | JDBC 시간 타입과 ISO 문자열을 모두 `OffsetDateTime`으로 안전하게 변환 |
| BG-FIX-02 | P1 | 3턴 Build Chat에서 직전 추천 후보와 원래 요구 문맥이 소실됨 | 되묻기 문맥을 누적하고 후보 지칭 후속 질문에도 다시 전달 |
| BG-FIX-03 | P1 | vector 검색 결과가 0건이면 keyword 검색으로 내려가지 않고 빈 결과 반환 | vector 결과가 실제로 존재할 때만 확정하고 0건이면 keyword fallback |
| BG-FIX-04 | P1 | `1000W 파워`가 정확 정격이 아닌 최소 정격으로 처리되고 임의 속성 문자열도 모델명 토큰으로 오인 | PSU 정격을 `EXACT/MIN/MAX`로 분리하고 신뢰 가능한 필드·수치만 비교 |
| BG-FIX-05 | P2 | 실제 컨트롤러 14개 operation이 OpenAPI에 없고 폐기 결제 API가 활성 경로처럼 남음 | 14개 operation 추가, 폐기 API를 410으로 명시, 결제 request 계약과 route 문서·QA 도구 갱신 |
| BG-FIX-06 | P2 | 데이터가 채워진 모바일 화면에서 관리자·견적 페이지의 고정 열 너비와 tooltip이 viewport를 침범 | 모바일 1열 전환, 관리자 내비게이션 가로 스크롤, tooltip viewport 고정 배치 적용 |

## 주요 재현 검증

### 결제 상태 전이

완성 견적에서 조립 요청을 생성하고 기사 제안을 선택한 뒤 `CARD` 결제 시도를 생성했다. 수정 전 500이 발생하던 경로가 `PROCESSING` 상태와 ISO 시간 값을 정상 반환했다. 상태형 데모 QA도 폐기된 `confirm-virtual` 대신 다음 실제 흐름을 사용하도록 바꿨다.

1. 결제 시도 생성
2. Mock 결과 `SUCCESS` 설정
3. provider 결과 재검증 및 완료
4. 조립 요청의 결제 상태 `PAID` 확인

### Build Chat 3턴 문맥

다음 체인을 실제 API로 확인했다.

1. `현재 메인보드에 맞는 CPU 추천해줘`
2. `첫 번째 후보를 적용하면 현재 구성에서 문제가 없는지 설명해줘`
3. `호환 안 되는 건 빼고 다른 선택지도 보여줘`

2턴 설명 응답에서 원래 추천 문맥을 유지했고, 3턴에서 CPU 후보 3개와 후속 칩 3개를 반환했다. 설명 요청이 견적 변경으로 오분류되거나 draft를 변경하지 않았다.

### RAG fallback

격리 DB에서 임베딩 값을 임시로 비활성화한 뒤 `/api/rag/search`를 호출했다. vector 결과가 0건인 상태에서도 keyword 검색으로 내려가 `total=6`, `items=3`을 반환했다. 검증 후 기존 임베딩 29건을 복원했다.

### 정확 1000W 파워

`2TB SSD와 1000W 파워 포함 500만원 견적 추천해줘`를 `gpt-5.4-mini` 경로로 실행했다. 반환된 3개 견적의 PSU가 모두 `capacityW=1000`이었고, 1200W 이상 제품이 정확 조건을 대신 충족하지 않았다.

## 자동 검증

| 검증 | 결과 |
|---|---|
| `./gradlew test --no-daemon` | 1,073건 완료, 실패 0, 오류 0, skip 4 |
| `./gradlew bootJar --no-daemon` | 성공 |
| `npm --prefix apps/web run test` | 355건 통과 |
| `npm --prefix apps/web run build` | 성공 |
| `python tools/validate_openapi.py docs/openapi.yaml` | 성공, 182 paths |
| `python -m py_compile tools/audit_demo_journey_stateful.py` | 성공 |
| `docker compose config --quiet` | 성공 |
| `git diff --check` | 오류 없음 |

## 버그와 분리한 잔여 품질 과제

아래 항목은 이번 감사에서 기능 오류로 확정하지 않았으며 별도 성능·품질 작업으로 관리한다.

- 일부 Build Chat live 요청 p95가 5초 목표를 넘는 구간
- RAG top1 정확도 목표 미달 케이스
- 웹 production bundle의 큰 JavaScript chunk 경고
- PC Agent 실제 실행 파일을 포함하는 CI 자동 연결 검증 보강

이번 수정은 위 과제를 해결했다고 간주하지 않는다. 또한 로컬 런타임 검증 데이터와 기존 감사 증거를 제품 코드에 포함하지 않았다.
