# BuildGraph PC Agent

4번 담당자의 PC Agent/AS 흐름 시작점입니다. 현재는 실제 Windows Service, tray app, installer가 아니라, AS 업로드 테스트에 사용할 JSON Lines 로그를 만들고 최근 30분 gzip 업로드를 수행하는 CLI입니다.

## 실행

Python 3.11 기준 CLI입니다. 저장소 루트에서 `scripts/setup-dev`를 실행하면 필요한 Python 의존성이 `.venv`에 설치됩니다.

```powershell
cd apps/pc-agent
pip install -r requirements.txt
python buildgraph_agent.py sample --out ../../seed/sample-agent-log.jsonl
python buildgraph_agent.py export --source ../../seed/sample-agent-log.jsonl --out recent-30m.jsonl --minutes 30
```

macOS/Linux에서 `pip` 또는 `python` 명령이 없다면 `pip3`, `python3`를 사용합니다.

## Goal 11/12 CLI

`agent-config.example.json`을 복사해 `agent-config.json`을 만들고, register를 실행했거나 Goal 10에서 받은 `agentToken`을 넣은 상태를 가정합니다.

```powershell
cd apps/pc-agent
python buildgraph_agent.py status --config ./agent-config.json
python buildgraph_agent.py doctor --config ./agent-config.json
python buildgraph_agent.py register --config ./agent-config.json
python buildgraph_agent.py collect --config ./agent-config.json --iterations 1
python buildgraph_agent.py upload --config ./agent-config.json --symptom "게임 중 프레임 드랍" --no-open
```

기본 수집 파일은 `logDir/agent-metrics.jsonl`입니다. `collect`는 기본 5초 간격이며, 검증용 기본값은 `--iterations 1`입니다. `--iterations 0`을 주면 계속 실행합니다.

업로드 명령은 다음을 수행합니다.

- 최근 30분 JSONL row 선택
- `recent-30m.jsonl.gz` 생성
- `POST /api/agent/log-uploads` multipart 요청 생성
- `Authorization: Bearer <agentToken>` 사용
- `Idempotency-Key` 생성 및 출력
- 응답의 `ticketId` 파싱
- 기본 브라우저로 `/support/{ticketId}` 열기, `--no-open`이면 URL만 출력

같은 `Idempotency-Key`를 재사용하려면 아래처럼 명시합니다.

```powershell
python buildgraph_agent.py upload --config ./agent-config.json --idempotency-key agent-upload-demo-001 --no-open
```

## 출력 예시

`status` 예시:

```text
BuildGraph PC Agent status
REGISTERED
```

`doctor` 예시:

```text
BuildGraph PC Agent doctor
config: ok
apiBaseUrl: http://localhost:8080
registration: REGISTERED
logDir: C:\...\apps\pc-agent\out\logs
logFile: out\logs\agent-metrics.jsonl
logBytes: 412
agentVersion: 0.1.0
policyVersion: policy-v1
agentToken: present
```

## 현재 역할

- CPU/RAM/Disk/GPU/온도/프로세스/오류 이벤트 형태의 샘플 로그 생성
- 최근 N분 로그 export
- 최근 30분 JSONL gzip 생성
- Agent token 기반 `POST /api/agent/log-uploads` 업로드
- 업로드 응답 `ticketId`로 `/support/{ticketId}` URL 생성

## 구현 시 지켜야 할 점

- JSONL 한 줄은 하나의 timestamp 관측치로 유지합니다.
- 실제 센서 수집을 붙이기 전에도 sample/export 명령은 계속 동작해야 합니다.
- 사용자 동의와 최근 30분 범위는 웹/API 흐름과 같은 필드명을 사용합니다.
- 원인 후보 분석 결과는 사용자 화면이 아니라 관리자 티켓 상세에서 다룹니다.
- register API 호출과 token 저장은 `register` 명령에서만 수행합니다.
- `/api/agent-logs/upload`가 아니라 `/api/agent/log-uploads`만 사용합니다.

## 이후 확장 후보

- `psutil`, NVML, Windows Event Log 수집 연결
- JSONL schema validation
- 문제 상황 재현용 sample profile 추가
- 업로드 파일 크기와 보관 기간 정책 적용
