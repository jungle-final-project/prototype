# PC Agent AS AI Fixture

## 목적

이 디렉터리는 AS 로그 기반 AI 진단/학습을 위한 50개 ticket 단위 fixture를 담는다. fixture는 `PCagent`의 최종 AS 시나리오를 참고하지만, prototype production DB/API를 변경하지 않는다.

## 구조

각 시나리오는 `docs/agent-as/fixtures/scenarios/{scenarioId}/` 아래에 다음 5개 파일을 가진다.

| 파일 | 의미 |
|---|---|
| `raw.jsonl` | PC Agent RawLog envelope 샘플. gzip이 아니라 리뷰 가능한 JSONL 텍스트로 저장한다. |
| `expected-log-summary.json` | 서버가 raw sample을 요약한 `LogSummary` 기대값. |
| `expected-routing.json` | 서버 rule/routing 결과 기대값. |
| `expected-ai-result.json` | AI가 생성해야 하는 `AiDiagnosisResult` 기대 shape. |
| `admin-label.json` | 관리자 승인/정답 label fixture. |

## RawLog envelope 필수 필드

`raw.jsonl`의 모든 line은 다음 필드를 가져야 한다.

- `schemaVersion`
- `collectedAt`
- `agentId`
- `sequence`
- `kind`
- `payload`
- `privacyFlags`

`payload`와 `privacyFlags`는 object여야 한다. `privacyFlags.containsRawPath=true`인 row는 반드시 `privacyFlags.masked=true`여야 한다.

## 금지 정책

- raw gzip 전체를 AI 입력으로 쓰지 않는다.
- 전체 JSONL을 학습 row로 쓰지 않는다.
- 전체 프로세스 목록, full path, token/password, 브라우저 기록은 fixture에 넣지 않는다.
- AI 결과는 ticket 확정값이 아니다. `proposedTicketPatch`는 기본적으로 비워 둔다.

## 생성/검증

fixture seed 재생성:

```powershell
python tools\generate_agent_as_fixture_seed.py
```

fixture 검증:

```powershell
python tools\validate_agent_as_fixtures.py
```

