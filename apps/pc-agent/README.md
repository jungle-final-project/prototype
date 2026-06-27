# BuildGraph PC Agent

4번 담당자의 PC Agent/AS 흐름 시작점입니다. 현재는 실제 백그라운드 수집 서비스가 아니라, AS 업로드 테스트에 사용할 JSON Lines 로그를 생성하고 최근 구간만 내보내는 CLI입니다.

## 실행

Python 3.11 기준 CLI입니다. 저장소 루트에서 `scripts/setup-dev`를 실행하면 필요한 Python 의존성이 `.venv`에 설치됩니다.

```powershell
cd apps/pc-agent
pip install -r requirements.txt
python buildgraph_agent.py sample --out ../../seed/sample-agent-log.jsonl
python buildgraph_agent.py export --source ../../seed/sample-agent-log.jsonl --out recent-30m.jsonl --minutes 30
```

macOS/Linux에서 `pip` 또는 `python` 명령이 없다면 `pip3`, `python3`를 사용합니다.

## 현재 역할

- CPU/RAM/Disk/GPU/온도/프로세스/오류 이벤트 형태의 샘플 로그 생성
- 최근 N분 로그 export
- 프론트 AS 접수와 백엔드 로그 업로드 흐름 테스트용 JSONL 제공

## 구현 시 지켜야 할 점

- JSONL 한 줄은 하나의 timestamp 관측치로 유지합니다.
- 실제 센서 수집을 붙이기 전에도 sample/export 명령은 계속 동작해야 합니다.
- 사용자 동의와 최근 30분 범위는 웹/API 흐름과 같은 필드명을 사용합니다.
- 원인 후보 분석 결과는 사용자 화면이 아니라 관리자 티켓 상세에서 다룹니다.

## 이후 확장 후보

- `psutil`, NVML, Windows Event Log 수집 연결
- JSONL schema validation
- 문제 상황 재현용 sample profile 추가
- 업로드 파일 크기와 보관 기간 정책 적용
